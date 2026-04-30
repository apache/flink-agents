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

module.exports = async ({ github, context, core }) => {
  const owner = context.repo.owner;
  const repo = context.repo.repo;

  // Search for issues closed as completed in the last 2 days without fixVersion/missing
  const twoDaysAgo = new Date();
  twoDaysAgo.setDate(twoDaysAgo.getDate() - 2);
  const sinceDate = twoDaysAgo.toISOString().split('T')[0];

  const query = `repo:${owner}/${repo} is:issue is:closed reason:completed -label:"fixVersion/missing" closed:>=${sinceDate}`;
  core.info(`Search query: ${query}`);

  const { data: searchResult } = await github.rest.search.issuesAndPullRequests({
    q: query,
    per_page: 100,
  });

  core.info(`Found ${searchResult.total_count} closed issues to check.`);

  let labeledCount = 0;

  for (const issue of searchResult.items) {
    const hasFixVersion = issue.labels.some(label => label.name.startsWith('fixVersion/'));

    if (!hasFixVersion) {
      await github.rest.issues.addLabels({
        owner,
        repo,
        issue_number: issue.number,
        labels: ['fixVersion/missing'],
      });
      labeledCount++;
      core.info(`Added 'fixVersion/missing' to issue #${issue.number}: ${issue.title}`);
    } else {
      core.info(`Issue #${issue.number} already has fixVersion label, skipping.`);
    }
  }

  core.info(`Done. Labeled ${labeledCount} issue(s) with 'fixVersion/missing'.`);
};
