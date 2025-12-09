<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

# How to contribute to Flink Agents

Thank you for your intention to contribute the Flink Agents project!

## Report a bug
1. Search in the [issues](https://github.com/apache/flink-agents/issues) to see if the bug has already been reported. If so, please add a comment to the existing issue instead of opening a new one.
2. If not, please open a new Bug Report issue.

## Propose or request a new feature
1. Search in the [issues](https://github.com/apache/flink-agents/issues) to see if the feature has already been requested. If so, please add a comment to the existing issue instead of opening a new one.
2. If not, please open a new Feature Request.

## Code contribution
1. For hotfix commits (typo fixes, minor pure refactors, etc.) that does not introduce any behavior changes, please open a pull request directly.
2. For other code changes, please open an issue about it and make sure there's at least one committer supports it before opening the pull request. And please link the issue in the pull request.
3. Please add the relevant components in the PR title. E.g., \[api\], \[runtime\], \[java\], \[python\], \[hotfix\], etc.

## Design discussions
1. Minor design changes can be discussed directly in the issue.
2. Significant design changes should be discussed as [ideas](https://github.com/apache/flink-agents/discussions/categories/ideas) with proper design docs. All issues for conducting the changes should link to the discussion thread.

## Testing

Run all the UT with the following command:

```shell
./tools/ut.sh
```

Only run Java UT:

```shell
./tools/ut.sh -j
```

Only run Python UT:

```shell
./tools/ut.sh -p
```

## Code Style

Run the following command to format the code:

```shell
./tools/lint.sh
```

## License headers

Run the following command to fix the license headers:

```shell
./tools/check-license.sh
```
