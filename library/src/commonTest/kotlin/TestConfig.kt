/*
 * Copyright 2026 Kaloyan Karaivanov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Global test configuration controlling test intensity.
 *
 * - **`local = true`** — Heavy workloads: large data sets, more coroutines, longer runs.
 *   Use when running on your development machine for thorough benchmarking.
 * - **`local = false`** — Lightweight workloads suitable for CI/CD or remote runners
 *   where resources and time are limited.
 *
 * Change this value before committing to match the target environment.
 */
object TestConfig {
    /** Set to `true` for heavy local benchmarks, `false` for lightweight CI runs. */
    const val LOCAL: Boolean = false
}
