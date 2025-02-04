// Copyright (C) 2022 The Android Open Source Project
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

package com.google.gerrit.acceptance.api.project;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.NoHttpd;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.extensions.common.SubmitRequirementInfo;
import com.google.gerrit.extensions.common.SubmitRequirementInput;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.inject.Inject;
import org.junit.Test;

@NoHttpd
public class SubmitRequirementsAPIIT extends AbstractDaemonTest {
  @Inject private RequestScopeOperations requestScopeOperations;

  @Test
  public void cannotGetANonExistingSR() throws Exception {
    ResourceNotFoundException thrown =
        assertThrows(
            ResourceNotFoundException.class,
            () -> gApi.projects().name(project.get()).submitRequirement("code-review").get());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Submit requirement 'code-review' does not exist");
  }

  @Test
  public void getExistingSR() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.applicabilityExpression = "topic:foo";
    input.submittabilityExpression = "label:code-review=+2";
    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();

    SubmitRequirementInfo info =
        gApi.projects().name(project.get()).submitRequirement("code-review").get();
    assertThat(info.name).isEqualTo("code-review");
    assertThat(info.applicabilityExpression).isEqualTo("topic:foo");
    assertThat(info.submittabilityExpression).isEqualTo("label:code-review=+2");
    assertThat(info.allowOverrideInChildProjects).isEqualTo(false);
  }

  @Test
  public void updateSubmitRequirement() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.applicabilityExpression = "topic:foo";
    input.submittabilityExpression = "label:code-review=+2";
    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();

    input.submittabilityExpression = "label:code-review=+1";
    SubmitRequirementInfo info =
        gApi.projects().name(project.get()).submitRequirement("code-review").update(input);
    assertThat(info.submittabilityExpression).isEqualTo("label:code-review=+1");
  }

  @Test
  public void updateSRWithEmptyApplicabilityExpression_isAllowed() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.applicabilityExpression = "topic:foo";
    input.submittabilityExpression = "label:code-review=+2";
    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();

    input.applicabilityExpression = null;
    SubmitRequirementInfo info =
        gApi.projects().name(project.get()).submitRequirement("code-review").update(input);
    assertThat(info.applicabilityExpression).isNull();
  }

  @Test
  public void updateSRWithEmptyOverrideExpression_isAllowed() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.overrideExpression = "topic:foo";
    input.submittabilityExpression = "label:code-review=+2";
    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();

    input.overrideExpression = null;
    SubmitRequirementInfo info =
        gApi.projects().name(project.get()).submitRequirement("code-review").update(input);
    assertThat(info.overrideExpression).isNull();
  }

  @Test
  public void allowOverrideInChildProjectsDefaultsToFalse_updateSR() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.submittabilityExpression = "label:code-review=+2";
    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();

    input.overrideExpression = "topic:foo";
    SubmitRequirementInfo info =
        gApi.projects().name(project.get()).submitRequirement("code-review").update(input);
    assertThat(info.allowOverrideInChildProjects).isFalse();
  }

  @Test
  public void cannotUpdateSRAsAnonymousUser() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.submittabilityExpression = "label:code-review=+2";

    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();
    input.submittabilityExpression = "label:code-review=+1";
    requestScopeOperations.setApiUserAnonymous();
    AuthException thrown =
        assertThrows(
            AuthException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .submitRequirement("code-review")
                    .update(new SubmitRequirementInput()));
    assertThat(thrown).hasMessageThat().contains("Authentication required");
  }

  @Test
  public void cannotUpdateSRtIfSRDoesNotExist() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.submittabilityExpression = "label:code-review=+2";

    ResourceNotFoundException thrown =
        assertThrows(
            ResourceNotFoundException.class,
            () ->
                gApi.projects().name(project.get()).submitRequirement("code-review").update(input));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Submit requirement 'code-review' does not exist");
  }

  @Test
  public void cannotUpdateSRWithEmptySubmittableIf() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.submittabilityExpression = "project:foo AND branch:refs/heads/main";

    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();
    input.submittabilityExpression = null;
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects().name(project.get()).submitRequirement("code-review").update(input));

    assertThat(thrown).hasMessageThat().isEqualTo("submittability_expression is required");
  }

  @Test
  public void cannotUpdateSRWithInvalidSubmittableIfExpression() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.submittabilityExpression = "project:foo AND branch:refs/heads/main";

    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();
    input.submittabilityExpression = "invalid_field:invalid_value";
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects().name(project.get()).submitRequirement("code-review").update(input));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Invalid submit requirement input: "
                + "[Invalid project configuration,   "
                + "project.config: Expression 'invalid_field:invalid_value' of "
                + "submit requirement 'code-review' "
                + "(parameter submit-requirement.code-review.submittableIf) is invalid: "
                + "Unsupported operator invalid_field:invalid_value]");
  }

  @Test
  public void cannotUpdateSRWithInvalidOverrideIfExpression() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.submittabilityExpression = "project:foo AND branch:refs/heads/main";

    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();
    input.overrideExpression = "invalid_field:invalid_value";
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects().name(project.get()).submitRequirement("code-review").update(input));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Invalid submit requirement input: "
                + "[Invalid project configuration,   "
                + "project.config: Expression 'invalid_field:invalid_value' of "
                + "submit requirement 'code-review' "
                + "(parameter submit-requirement.code-review.overrideIf) is invalid: "
                + "Unsupported operator invalid_field:invalid_value]");
  }

  @Test
  public void cannotUpdateSRWithInvalidApplicableIfExpression() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.submittabilityExpression = "project:foo AND branch:refs/heads/main";

    gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();
    input.applicabilityExpression = "invalid_field:invalid_value";
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects().name(project.get()).submitRequirement("code-review").update(input));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Invalid submit requirement input: "
                + "[Invalid project configuration,   "
                + "project.config: Expression 'invalid_field:invalid_value' of "
                + "submit requirement 'code-review' "
                + "(parameter submit-requirement.code-review.applicableIf) is invalid: "
                + "Unsupported operator invalid_field:invalid_value]");
  }

  @Test
  public void createSubmitRequirement() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.applicabilityExpression = "project:foo AND branch:refs/heads/main";
    input.submittabilityExpression = "label:code-review=+2";
    input.overrideExpression = "label:build-cop-override=+1";
    input.allowOverrideInChildProjects = true;

    SubmitRequirementInfo info =
        gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();

    assertThat(info.name).isEqualTo("code-review");
    assertThat(info.description).isEqualTo(input.description);
    assertThat(info.applicabilityExpression).isEqualTo(input.applicabilityExpression);
    assertThat(info.applicabilityExpression).isEqualTo(input.applicabilityExpression);
    assertThat(info.submittabilityExpression).isEqualTo(input.submittabilityExpression);
    assertThat(info.overrideExpression).isEqualTo(input.overrideExpression);
    assertThat(info.allowOverrideInChildProjects).isEqualTo(true);
  }

  @Test
  public void createSRWithEmptyApplicabilityExpression_isAllowed() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.submittabilityExpression = "label:code-review=+2";
    input.overrideExpression = "label:build-cop-override=+1";

    SubmitRequirementInfo info =
        gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();

    assertThat(info.name).isEqualTo("code-review");
    assertThat(info.applicabilityExpression).isNull();
  }

  @Test
  public void createSRWithEmptyOverrideExpression_isAllowed() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.applicabilityExpression = "project:foo AND branch:refs/heads/main";
    input.submittabilityExpression = "label:code-review=+2";

    SubmitRequirementInfo info =
        gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();

    assertThat(info.name).isEqualTo("code-review");
    assertThat(info.overrideExpression).isNull();
  }

  @Test
  public void allowOverrideInChildProjectsDefaultsToFalse_createSR() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.submittabilityExpression = "label:code-review=+2";

    SubmitRequirementInfo info =
        gApi.projects().name(project.get()).submitRequirement("code-review").create(input).get();

    assertThat(info.allowOverrideInChildProjects).isEqualTo(false);
  }

  @Test
  public void cannotCreateSRAsAnonymousUser() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.applicabilityExpression = "project:foo AND branch:refs/heads/main";
    input.submittabilityExpression = "label:code-review=+2";
    input.overrideExpression = "label:build-cop-override=+1";

    requestScopeOperations.setApiUserAnonymous();
    AuthException thrown =
        assertThrows(
            AuthException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .submitRequirement("code-review")
                    .create(new SubmitRequirementInput()));
    assertThat(thrown).hasMessageThat().contains("Authentication required");
  }

  @Test
  public void cannotCreateSRtIfNameInInputDoesNotMatchResource() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.submittabilityExpression = "label:code-review=+2";

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .submitRequirement("other-requirement")
                    .create(input)
                    .get());
    assertThat(thrown).hasMessageThat().isEqualTo("name in input must match name in URL");
  }

  @Test
  public void cannotCreateSRWithInvalidName() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "wrong$%";
    input.submittabilityExpression = "label:code-review=+2";

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .submitRequirement("wrong$%")
                    .create(input)
                    .get());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Illegal submit requirement name \"wrong$%\". "
                + "Name can only consist of alphanumeric characters and '-'."
                + " Name cannot start with '-' or number.");
  }

  @Test
  public void cannotCreateSRWithEmptySubmittableIf() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.applicabilityExpression = "project:foo AND branch:refs/heads/main";
    input.overrideExpression = "label:build-cop-override=+1";

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .submitRequirement("code-review")
                    .create(input)
                    .get());

    assertThat(thrown).hasMessageThat().isEqualTo("submittability_expression is required");
  }

  @Test
  public void cannotCreateSRWithInvalidSubmittableIfExpression() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.submittabilityExpression = "invalid_field:invalid_value";
    BadRequestException exception =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .submitRequirement("code-review")
                    .create(input)
                    .get());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "Invalid submit requirement input: "
                + "[Invalid project configuration,   "
                + "project.config: Expression 'invalid_field:invalid_value' of "
                + "submit requirement 'code-review' "
                + "(parameter submit-requirement.code-review.submittableIf) is invalid: "
                + "Unsupported operator invalid_field:invalid_value]");
  }

  @Test
  public void cannotCreateSRWithInvalidOverrideIfExpression() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.submittabilityExpression = "label:Code-Review=+2";
    input.overrideExpression = "invalid_field:invalid_value";
    BadRequestException exception =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .submitRequirement("code-review")
                    .create(input)
                    .get());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "Invalid submit requirement input: "
                + "[Invalid project configuration,   "
                + "project.config: Expression 'invalid_field:invalid_value' of "
                + "submit requirement 'code-review' "
                + "(parameter submit-requirement.code-review.overrideIf) is invalid: "
                + "Unsupported operator invalid_field:invalid_value]");
  }

  @Test
  public void cannotCreateSRWithInvalidApplicableIfExpression() throws Exception {
    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = "code-review";
    input.description = "At least one +2 vote to the code-review label";
    input.applicabilityExpression = "invalid_field:invalid_value";
    input.submittabilityExpression = "label:Code-Review=+2";
    BadRequestException exception =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .submitRequirement("code-review")
                    .create(input)
                    .get());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "Invalid submit requirement input: "
                + "[Invalid project configuration,   "
                + "project.config: Expression 'invalid_field:invalid_value' of "
                + "submit requirement 'code-review' "
                + "(parameter submit-requirement.code-review.applicableIf) is invalid: "
                + "Unsupported operator invalid_field:invalid_value]");
  }
}
