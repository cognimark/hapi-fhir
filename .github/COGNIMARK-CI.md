# Fork Dependency Qualification

The Cognimark dependency workflow runs the core/client/server reactor tests and
embedded SQL migrations on Maven 3.9.12 / Java 21, then exercises PostgreSQL 16
in a disposable container. It rejects skipped PostgreSQL tests and retains the
two test-report sets separately. Published library bytecode remains Java 17.

This bounded suite is not the entire upstream test matrix: Oracle, SQL Server,
Elasticsearch and the full JPA test suites need separate qualification when
affected. CodeQL separately compiles the full reactor without tests. A green
workflow is not an independent review, production upgrade or closure of all
dependency alerts.

The upstream PR matrix and formatting-comment proxy run only in the upstream
repository. The fork does not need upstream Codecov, GitLab or comment-proxy
credentials. Its jobs use read-only repository permissions, except for CodeQL
result publication, and cannot deploy a service. CodeQL and dependency tests
use distinct concurrency groups and explicit timeouts.

The source-security branch targets the fork's default branch. It does not change
the separately pinned production storage branch or Core's library version.
