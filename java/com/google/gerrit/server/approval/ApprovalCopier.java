// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.approval;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.gerrit.server.project.ProjectCache.illegalState;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.LabelType;
import com.google.gerrit.entities.LabelTypes;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Project;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.index.query.QueryParseException;
import com.google.gerrit.server.change.ChangeKindCache;
import com.google.gerrit.server.change.LabelNormalizer;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.patch.DiffNotAvailableException;
import com.google.gerrit.server.patch.DiffOperations;
import com.google.gerrit.server.patch.DiffOptions;
import com.google.gerrit.server.patch.gitdiff.ModifiedFile;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.approval.ApprovalContext;
import com.google.gerrit.server.query.approval.ApprovalQueryBuilder;
import com.google.gerrit.server.query.approval.ListOfFilesUnchangedPredicate;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Computes copied approvals for a given patch set.
 *
 * <p>Approvals are copied if:
 *
 * <ul>
 *   <li>the approval on the previous patch set matches the copy condition of its label
 *   <li>the approval is not overridden by a current approval on the patch set
 * </ul>
 *
 * <p>Callers should store the copied approvals in NoteDb when a new patch set is created.
 */
@Singleton
@VisibleForTesting
public class ApprovalCopier {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @AutoValue
  public abstract static class Result {
    /**
     * Approvals that have been copied from the previous patch set.
     *
     * <p>An approval is copied if:
     *
     * <ul>
     *   <li>the approval on the previous patch set matches the copy condition of its label
     *   <li>the approval is not overridden by a current approval on the patch set
     * </ul>
     */
    public abstract ImmutableSet<PatchSetApproval> copiedApprovals();

    /**
     * Approvals on the previous patch set that have not been copied to the patch set.
     *
     * <p>These approvals didn't match the copy condition of their labels and hence haven't been
     * copied.
     *
     * <p>Only returns non-copied approvals of the previous patch set. Approvals from earlier patch
     * sets that were outdated before are not included.
     */
    public abstract ImmutableSet<PatchSetApproval> outdatedApprovals();

    static Result empty() {
      return create(
          /* copiedApprovals= */ ImmutableSet.of(), /* outdatedApprovals= */ ImmutableSet.of());
    }

    static Result create(
        ImmutableSet<PatchSetApproval> copiedApprovals,
        ImmutableSet<PatchSetApproval> outdatedApprovals) {
      return new AutoValue_ApprovalCopier_Result(copiedApprovals, outdatedApprovals);
    }
  }

  private final DiffOperations diffOperations;
  private final ProjectCache projectCache;
  private final ChangeKindCache changeKindCache;
  private final LabelNormalizer labelNormalizer;
  private final ApprovalQueryBuilder approvalQueryBuilder;
  private final OneOffRequestContext requestContext;
  private final ListOfFilesUnchangedPredicate listOfFilesUnchangedPredicate;

  @Inject
  ApprovalCopier(
      DiffOperations diffOperations,
      ProjectCache projectCache,
      ChangeKindCache changeKindCache,
      LabelNormalizer labelNormalizer,
      ApprovalQueryBuilder approvalQueryBuilder,
      OneOffRequestContext requestContext,
      ListOfFilesUnchangedPredicate listOfFilesUnchangedPredicate) {
    this.diffOperations = diffOperations;
    this.projectCache = projectCache;
    this.changeKindCache = changeKindCache;
    this.labelNormalizer = labelNormalizer;
    this.approvalQueryBuilder = approvalQueryBuilder;
    this.requestContext = requestContext;
    this.listOfFilesUnchangedPredicate = listOfFilesUnchangedPredicate;
  }

  /**
   * Returns all copied approvals that apply to the given patch set.
   *
   * <p>Approvals are copied if:
   *
   * <ul>
   *   <li>the approval on the previous patch set matches the copy condition of its label
   *   <li>the approval is not overridden by a current approval on the patch set
   * </ul>
   */
  @VisibleForTesting
  public Result forPatchSet(ChangeNotes notes, PatchSet ps, RevWalk rw, Config repoConfig) {
    ProjectState project;
    try (TraceTimer traceTimer =
        TraceContext.newTimer(
            "Computing labels for patch set",
            Metadata.builder()
                .changeId(notes.load().getChangeId().get())
                .patchSetId(ps.id().get())
                .build())) {
      project =
          projectCache
              .get(notes.getProjectName())
              .orElseThrow(illegalState(notes.getProjectName()));
      return computeForPatchSet(project.getLabelTypes(), notes, ps, rw, repoConfig);
    }
  }

  private boolean canCopyBasedOnBooleanLabelConfigs(
      Project.NameKey projectName,
      PatchSet.Id sourcePsId,
      PatchSet.Id targetPsId,
      LabelType labelType,
      short approvalValue,
      ChangeKind kind,
      boolean isMerge,
      @Nullable Map<String, ModifiedFile> baseVsCurrentDiff,
      @Nullable Map<String, ModifiedFile> baseVsPriorDiff,
      @Nullable Map<String, ModifiedFile> priorVsCurrentDiff) {
    Change.Id changeId = sourcePsId.changeId();
    checkArgument(changeId.equals(targetPsId.changeId()));

    int n = sourcePsId.get();
    checkArgument(n != targetPsId.get());

    if (labelType.isCopyMinScore() && labelType.isMaxNegative(approvalValue)) {
      logger.atFine().log(
          "veto approval %s on label %s of patch set %d of change %d can be copied"
              + " to patch set %d because the label has set copyMinScore = true on project %s",
          approvalValue,
          labelType.getLabelId().get(),
          n,
          changeId.get(),
          targetPsId.get(),
          projectName);
      return true;
    } else if (labelType.isCopyMaxScore() && labelType.isMaxPositive(approvalValue)) {
      logger.atFine().log(
          "max approval %s on label %s of patch set %d of change %d can be copied"
              + " to patch set %d because the label has set copyMaxScore = true on project %s",
          approvalValue,
          labelType.getLabelId().get(),
          n,
          changeId.get(),
          targetPsId.get(),
          projectName);
      return true;
    } else if (labelType.isCopyAnyScore()) {
      logger.atFine().log(
          "approval %d on label %s of patch set %d of change %d can be copied"
              + " to patch set %d because the label has set copyAnyScore = true on project %s",
          approvalValue,
          labelType.getLabelId().get(),
          n,
          changeId.get(),
          targetPsId.get(),
          projectName);
      return true;
    } else if (labelType.getCopyValues().contains(approvalValue)) {
      logger.atFine().log(
          "approval %d on label %s of patch set %d of change %d can be copied"
              + " to patch set %d because the label has set copyValue = %d on project %s",
          approvalValue,
          labelType.getLabelId().get(),
          n,
          changeId.get(),
          targetPsId.get(),
          approvalValue,
          projectName);
      return true;
    } else if (labelType.isCopyAllScoresIfListOfFilesDidNotChange()
        && listOfFilesUnchangedPredicate.match(
            baseVsCurrentDiff, baseVsPriorDiff, priorVsCurrentDiff)) {
      logger.atFine().log(
          "approval %d on label %s of patch set %d of change %d can be copied"
              + " to patch set %d because the label has set "
              + "copyAllScoresIfListOfFilesDidNotChange = true on "
              + "project %s and list of files did not change (maybe except a rename, which is "
              + "still the same file).",
          approvalValue,
          labelType.getLabelId().get(),
          n,
          changeId.get(),
          targetPsId.get(),
          projectName);
      return true;
    }
    switch (kind) {
      case MERGE_FIRST_PARENT_UPDATE:
        if (labelType.isCopyAllScoresOnMergeFirstParentUpdate()) {
          logger.atFine().log(
              "approval %d on label %s of patch set %d of change %d can be copied"
                  + " to patch set %d because change kind is %s and the label has set"
                  + " copyAllScoresOnMergeFirstParentUpdate = true on project %s",
              approvalValue,
              labelType.getLabelId().get(),
              n,
              changeId.get(),
              targetPsId.get(),
              kind,
              projectName);
          return true;
        }
        return false;
      case NO_CODE_CHANGE:
        if (labelType.isCopyAllScoresIfNoCodeChange()) {
          logger.atFine().log(
              "approval %d on label %s of patch set %d of change %d can be copied"
                  + " to patch set %d because change kind is %s and the label has set"
                  + " copyAllScoresIfNoCodeChange = true on project %s",
              approvalValue,
              labelType.getLabelId().get(),
              n,
              changeId.get(),
              targetPsId.get(),
              kind,
              projectName);
          return true;
        }
        return false;
      case TRIVIAL_REBASE:
        if (labelType.isCopyAllScoresOnTrivialRebase()) {
          logger.atFine().log(
              "approval %d on label %s of patch set %d of change %d can be copied"
                  + " to patch set %d because change kind is %s and the label has set"
                  + " copyAllScoresOnTrivialRebase = true on project %s",
              approvalValue,
              labelType.getLabelId().get(),
              n,
              changeId.get(),
              targetPsId.get(),
              kind,
              projectName);
          return true;
        }
        return false;
      case NO_CHANGE:
        if (labelType.isCopyAllScoresIfNoChange()) {
          logger.atFine().log(
              "approval %d on label %s of patch set %d of change %d can be copied"
                  + " to patch set %d because change kind is %s and the label has set"
                  + " copyAllScoresIfNoCodeChange = true on project %s",
              approvalValue,
              labelType.getLabelId().get(),
              n,
              changeId.get(),
              targetPsId.get(),
              kind,
              projectName);
          return true;
        }
        if (labelType.isCopyAllScoresOnTrivialRebase()) {
          logger.atFine().log(
              "approval %d on label %s of patch set %d of change %d can be copied"
                  + " to patch set %d because change kind is %s and the label has set"
                  + " copyAllScoresOnTrivialRebase = true on project %s",
              approvalValue,
              labelType.getLabelId().get(),
              n,
              changeId.get(),
              targetPsId.get(),
              kind,
              projectName);
          return true;
        }
        if (isMerge && labelType.isCopyAllScoresOnMergeFirstParentUpdate()) {
          logger.atFine().log(
              "approval %d on label %s of patch set %d of change %d can be copied"
                  + " to patch set %d because change kind is %s and the label has set"
                  + " copyAllScoresOnMergeFirstParentUpdate = true on project %s",
              approvalValue,
              labelType.getLabelId().get(),
              n,
              changeId.get(),
              targetPsId.get(),
              kind,
              projectName);
          return true;
        }
        if (labelType.isCopyAllScoresIfNoCodeChange()) {
          logger.atFine().log(
              "approval %d on label %s of patch set %d of change %d can be copied"
                  + " to patch set %d because change kind is %s and the label has set"
                  + " copyAllScoresIfNoCodeChange = true on project %s",
              approvalValue,
              labelType.getLabelId().get(),
              n,
              changeId.get(),
              targetPsId.get(),
              kind,
              projectName);
          return true;
        }
        return false;
      case REWORK:
      default:
        logger.atFine().log(
            "approval %d on label %s of patch set %d of change %d cannot be copied"
                + " to patch set %d because change kind is %s",
            approvalValue, labelType.getLabelId().get(), n, changeId.get(), targetPsId.get(), kind);
        return false;
    }
  }

  private boolean canCopyBasedOnCopyCondition(
      ChangeNotes changeNotes,
      PatchSet.Id sourcePatchSetId,
      PatchSet targetPatchSet,
      Account.Id approverId,
      LabelType labelType,
      short approvalValue,
      ChangeKind changeKind,
      boolean isMerge,
      RevWalk revWalk,
      Config repoConfig) {
    if (!labelType.getCopyCondition().isPresent()) {
      return false;
    }
    ApprovalContext ctx =
        ApprovalContext.create(
            changeNotes,
            sourcePatchSetId,
            approverId,
            labelType,
            approvalValue,
            targetPatchSet,
            changeKind,
            isMerge,
            revWalk,
            repoConfig);
    try {
      // Use a request context to run checks as an internal user with expanded visibility. This is
      // so that the output of the copy condition does not depend on who is running the current
      // request (e.g. a group used in this query might not be visible to the person sending this
      // request).
      try (ManualRequestContext ignored = requestContext.open()) {
        return approvalQueryBuilder
            .parse(labelType.getCopyCondition().get())
            .asMatchable()
            .match(ctx);
      }
    } catch (QueryParseException e) {
      logger.atWarning().withCause(e).log(
          "Unable to copy label because config is invalid. This should have been caught before.");
      return false;
    }
  }

  private Result computeForPatchSet(
      LabelTypes labelTypes,
      ChangeNotes notes,
      PatchSet targetPatchSet,
      RevWalk rw,
      Config repoConfig) {
    Project.NameKey projectName = notes.getProjectName();
    PatchSet.Id targetPsId = targetPatchSet.id();

    // Bail out immediately if this is the first patch set. Return only approvals granted on the
    // given patch set.
    if (targetPsId.get() == 1) {
      return Result.empty();
    }
    Map.Entry<PatchSet.Id, PatchSet> priorPatchSet =
        notes.load().getPatchSets().lowerEntry(targetPsId);
    if (priorPatchSet == null) {
      return Result.empty();
    }

    Table<String, Account.Id, PatchSetApproval> currentApprovalsByUser = HashBasedTable.create();
    ImmutableList<PatchSetApproval> nonCopiedApprovalsForGivenPatchSet =
        notes.load().getApprovals().onlyNonCopied().get(targetPatchSet.id());
    nonCopiedApprovalsForGivenPatchSet.forEach(
        psa -> currentApprovalsByUser.put(psa.label(), psa.accountId(), psa));

    Table<String, Account.Id, PatchSetApproval> copiedApprovalsByUser = HashBasedTable.create();
    ImmutableSet.Builder<PatchSetApproval> outdatedApprovalsBuilder = ImmutableSet.builder();

    ImmutableList<PatchSetApproval> priorApprovals =
        notes.load().getApprovals().all().get(priorPatchSet.getKey());

    // Add labels from the previous patch set to the result in case the label isn't already there
    // and settings as well as change kind allow copying.
    ChangeKind changeKind =
        changeKindCache.getChangeKind(
            projectName,
            rw,
            repoConfig,
            priorPatchSet.getValue().commitId(),
            targetPatchSet.commitId());
    boolean isMerge = isMerge(projectName, rw, targetPatchSet);
    logger.atFine().log(
        "change kind for patch set %d of change %d against prior patch set %s is %s",
        targetPatchSet.id().get(),
        targetPatchSet.id().changeId().get(),
        priorPatchSet.getValue().id().changeId(),
        changeKind);

    Map<String, ModifiedFile> baseVsCurrent = null;
    Map<String, ModifiedFile> baseVsPrior = null;
    Map<String, ModifiedFile> priorVsCurrent = null;
    for (PatchSetApproval priorPsa : priorApprovals) {
      Optional<LabelType> labelType = labelTypes.byLabel(priorPsa.labelId());
      // Only compute modified files if there is a relevant label, since this is expensive.
      if (baseVsCurrent == null
          && labelType.isPresent()
          && labelType.get().isCopyAllScoresIfListOfFilesDidNotChange()) {
        baseVsCurrent = listModifiedFiles(projectName, targetPatchSet, rw, repoConfig);
        baseVsPrior = listModifiedFiles(projectName, priorPatchSet.getValue(), rw, repoConfig);
        priorVsCurrent =
            listModifiedFiles(
                projectName,
                priorPatchSet.getValue().commitId(),
                targetPatchSet.commitId(),
                rw,
                repoConfig);
      }
      if (!labelType.isPresent()) {
        logger.atFine().log(
            "approval %d on label %s of patch set %d of change %d cannot be copied"
                + " to patch set %d because the label no longer exists on project %s",
            priorPsa.value(),
            priorPsa.label(),
            priorPsa.key().patchSetId().get(),
            priorPsa.key().patchSetId().changeId().get(),
            targetPsId.get(),
            projectName);
        outdatedApprovalsBuilder.add(priorPsa);
        continue;
      }
      if (!canCopyBasedOnBooleanLabelConfigs(
              projectName,
              priorPsa.patchSetId(),
              targetPatchSet.id(),
              labelType.get(),
              priorPsa.value(),
              changeKind,
              isMerge,
              baseVsCurrent,
              baseVsPrior,
              priorVsCurrent)
          && !canCopyBasedOnCopyCondition(
              notes,
              priorPsa.patchSetId(),
              targetPatchSet,
              priorPsa.accountId(),
              labelType.get(),
              priorPsa.value(),
              changeKind,
              isMerge,
              rw,
              repoConfig)) {
        outdatedApprovalsBuilder.add(priorPsa);
        continue;
      }
      if (!currentApprovalsByUser.contains(priorPsa.label(), priorPsa.accountId())) {
        copiedApprovalsByUser.put(
            priorPsa.label(), priorPsa.accountId(), priorPsa.copyWithPatchSet(targetPatchSet.id()));
      }
    }

    ImmutableSet<PatchSetApproval> copiedApprovals =
        labelNormalizer.normalize(notes, copiedApprovalsByUser.values()).getNormalized();
    return Result.create(copiedApprovals, outdatedApprovalsBuilder.build());
  }

  private boolean isMerge(Project.NameKey project, RevWalk rw, PatchSet patchSet) {
    try {
      return rw.parseCommit(patchSet.commitId()).getParentCount() > 1;
    } catch (IOException e) {
      throw new StorageException(
          String.format(
              "failed to check if patch set %d of change %s in project %s is a merge commit",
              patchSet.id().get(), patchSet.id().changeId(), project),
          e);
    }
  }

  /**
   * Gets the modified files between the two latest patch-sets. Can be used to compute difference in
   * files between those two patch-sets .
   */
  private Map<String, ModifiedFile> listModifiedFiles(
      Project.NameKey projectName, PatchSet ps, RevWalk revWalk, Config repoConfig) {
    try {
      Integer parentNum =
          listOfFilesUnchangedPredicate.isInitialCommit(projectName, ps.commitId()) ? 0 : 1;
      return diffOperations.loadModifiedFilesAgainstParent(
          projectName, ps.commitId(), parentNum, DiffOptions.DEFAULTS, revWalk, repoConfig);
    } catch (DiffNotAvailableException ex) {
      throw new StorageException(
          "failed to compute difference in files, so won't copy"
              + " votes on labels even if list of files is the same and "
              + "copyAllIfListOfFilesDidNotChange",
          ex);
    }
  }

  /**
   * Gets the modified files between two commits corresponding to different patchsets of the same
   * change.
   */
  private Map<String, ModifiedFile> listModifiedFiles(
      Project.NameKey projectName,
      ObjectId sourceCommit,
      ObjectId targetCommit,
      RevWalk revWalk,
      Config repoConfig) {
    try {
      return diffOperations.loadModifiedFiles(
          projectName, sourceCommit, targetCommit, DiffOptions.DEFAULTS, revWalk, repoConfig);
    } catch (DiffNotAvailableException ex) {
      throw new StorageException(
          "failed to compute difference in files, so won't copy"
              + " votes on labels even if list of files is the same and "
              + "copyAllIfListOfFilesDidNotChange",
          ex);
    }
  }
}
