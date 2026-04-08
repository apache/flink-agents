/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

module.exports = async ({ github, context, core, workflowRunId, triggerWorkflow }) => {
  // Retrieve the PR number from the workflow run that triggered this job
  const { data: run } = await github.rest.actions.getWorkflowRun({
    owner: context.repo.owner,
    repo: context.repo.repo,
    run_id: workflowRunId,
  });
  let prNumber = run.pull_requests?.[0]?.number;

  // Fallback for fork PRs: pull_requests is empty for cross-repo workflow runs
  if (!prNumber) {
    core.info(`No PR in pull_requests array, falling back to search by head branch...`);
    // Search all PRs (including closed) to avoid errors when editing closed PRs
    const { data: pullRequests } = await github.rest.pulls.list({
      owner: context.repo.owner,
      repo: context.repo.repo,
      state: 'all',
      head: `${run.head_repository.owner.login}:${run.head_branch}`,
    });
    prNumber = pullRequests[0]?.number;
  }

  if (!prNumber) {
    core.setFailed(`No pull request found for workflow run ${workflowRunId}`);
    return;
  }
  core.info(`Found PR #${prNumber} from workflow run ${workflowRunId}`);

  const { data: pr } = await github.rest.pulls.get({
    owner: context.repo.owner,
    repo: context.repo.repo,
    pull_number: prNumber,
  });

  // Skip labeling for closed/merged PRs — editing them should not trigger label changes
  if (pr.state !== 'open') {
    core.info(`PR #${prNumber} is ${pr.state}, skipping labeling.`);
    return;
  }

  const isOpenTrigger = triggerWorkflow === 'PR-Open-Trigger';
  const labels = [];

  // --- Labels only added on PR open (priority/major, fixVersion) ---

  if (isOpenTrigger) {
    labels.push('priority/major');

    const baseBranch = pr.base.ref;
    const fixVersionLabelMap = {
      'main': 'fixVersion/0.3.0',
      'release-0.2': 'fixVersion/0.2.2',
      'release-0.1': 'fixVersion/0.1.2',
    };
    const fixVersionLabel = fixVersionLabelMap[baseBranch];
    if (fixVersionLabel) {
      labels.push(fixVersionLabel);
    }
  }

  // --- Documentation labels (evaluated on both open and edit) ---

  const docLabels = ['doc-needed', 'doc-not-needed', 'doc-included'];
  const docMissingLabel = 'doc-label-missing';
  const prBody = pr.body || '';
  const checkedDocLabel = docLabels.find(label => {
    const pattern = new RegExp(`-\\s*\\[x\\]\\s*\`${label}\``, 'i');
    return pattern.test(prBody);
  });

  // Remove all existing doc labels first
  const { data: existingLabels } = await github.rest.issues.listLabelsOnIssue({
    owner: context.repo.owner,
    repo: context.repo.repo,
    issue_number: prNumber,
  });
  const allDocLabels = [...docLabels, docMissingLabel];
  for (const existingLabel of existingLabels) {
    if (allDocLabels.includes(existingLabel.name)) {
      await github.rest.issues.removeLabel({
        owner: context.repo.owner,
        repo: context.repo.repo,
        issue_number: prNumber,
        name: existingLabel.name,
      });
      core.info(`Removed existing doc label '${existingLabel.name}'.`);
    }
  }

  if (checkedDocLabel) {
    labels.push(checkedDocLabel);
    core.info(`Documentation checkbox checked: '${checkedDocLabel}'.`);
  } else {
    labels.push(docMissingLabel);
    core.info('No documentation checkbox checked, adding doc-label-missing.');
  }

  // --- Apply all labels ---

  if (labels.length > 0) {
    await github.rest.issues.addLabels({
      owner: context.repo.owner,
      repo: context.repo.repo,
      issue_number: prNumber,
      labels: labels,
    });
    core.info(`Added labels: ${labels.join(', ')}`);
  } else {
    core.info('No labels to add.');
  }
};
