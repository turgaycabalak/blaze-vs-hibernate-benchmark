# Blaze-Persistence vs Hibernate 7 — reproducible benchmarks

Do you still need [Blaze-Persistence](https://persistence.blazebit.com/) with a current Hibernate?
This repository measures the four areas Blaze is known for against **Hibernate 7.4 / Spring Data JPA 4.1**
(and, where it matters, Hibernate 7.2), on PostgreSQL 18 with a 1M-order dataset.

Every benchmark first checks that all strategies return exactly the same result, then measures.
Numbers are from one machine; run it yourself and compare the shapes of the curves, not the absolute values.

## Results at a glance

| Scenario | Finding (median latency) | Blaze needed with Hibernate 7.4? |
|---|---|---|
| **Keyset pagination** (page 25,000 of 50,000) | Blaze keyset 1.7 ms, OFFSET 251 ms. Spring Data `Window` and Hibernate `KeyedPage` 248 ms: their `a < ? OR (a = ? AND b < ?)` predicate is not an index range on PostgreSQL. A hand-written HQL row-value predicate `(a, b) < (?, ?)` 1.4 ms. | No: one HQL query does the same |
| **Collection fetch + pagination** (250k matching orders) | Hibernate 7.2: `join fetch` + `setMaxResults` pages in memory, 3.2 s and 1.1 GB per request. Hibernate 7.4 pages in SQL: 2.0 ms. Blaze 2.9 ms on both. Subselect fetch has no LIMIT: 2.7 s. | No on 7.4; yes before 7.4 (Spring Boot 4.0) |
| **Entity views / projections** (order list with nested lines) | Lazy entities mapped to DTOs and Spring Data interface projections with a nested collection both do N+1 (5,001 statements for 1,000 orders, ~2 s). Blaze Entity View: 1 statement, fastest up to 200 orders per page. Hand-written HQL DTOs: 2 statements, fastest at 1,000. | For less code, not for speed |
| **CTE / window function / subquery in FROM** (latest order per customer) | HQL (Hibernate 6.2+) CTEs and derived tables: 884 ms for 100k customers. Blaze: 12.5 s, and it drops equal rows (see below). | No |

### Things that surprised me

- **Blaze 1.6.20 de-duplicates the results of CTE / FROM-subquery queries on Hibernate 7.** They run through
  Blaze's `HibernateExtendedQuerySupport`, which asks Hibernate for `UniqueSemantic.FILTER`: equal rows are
  silently dropped (100 expected, 8 returned) and each row is compared with all previous ones, O(n²).
  See [`BlazeExtendedQueryDedupTest`](src/test/java/dev/blazebench/cte/BlazeExtendedQueryDedupTest.java) and
  [`results/latest-order/blaze-profile.txt`](results/latest-order/blaze-profile.txt).
- **Blaze 1.6.20 does not start under a Turkish default locale** (`"Integer".toLowerCase()` is `"ınteger"`).
  The build pins `-Duser.language=en` for tests (see `pom.xml`).
- **Built-in keyset pagination looks fast on the last page only**, because few rows are left behind the key.
  Test a middle page.
- **Blaze paginated queries run a COUNT by default** (~27 ms on 1M rows); keyset needs `withCountQuery(false)`.

## Stack

Java 25 · Spring Boot 4.1.1 · Hibernate ORM 7.4.5 · Spring Data JPA 4.1.1 · Blaze-Persistence 1.6.20 ·
PostgreSQL 18 (Testcontainers) · Maven

## Running it

Requirements: JDK 25, Maven 3.9+, Docker.

```bash
# correctness tests on a small dataset (~1 minute)
mvn test

# all benchmarks on the full dataset (1M orders, ~40 s seed; ~45 minutes in total)
mvn test -Pbenchmark

# a single benchmark
mvn test -Pbenchmark -Dtest=PaginationBenchmark

# collection fetch on Hibernate 7.2 (what Spring Boot 4.0 ships), for the before/after comparison
mvn test -Pbenchmark -Phibernate72 -Dtest=CollectionFetchBenchmark
```

Keep the machine idle while benchmarks run: background load (a video in a browser was enough) shifts the numbers
by 35–50 %.

| Benchmark | What varies | Output |
|---|---|---|
| `PaginationBenchmark`, `PaginationPlansBenchmark` | page number (1 … 50,000) | `results/pagination/` |
| `CollectionFetchBenchmark` | orders matching the filter (1k … 250k) | `results/collection-fetch*/` |
| `ProjectionBenchmark` | orders per page (10 … 1,000) | `results/projection/` |
| `LatestOrderBenchmark`, `LatestOrderDiagnosticsBenchmark` | customers in scope (100 … 100k) | `results/latest-order/` |

Each results folder has `summary.csv` (p50 / p95 / p99, allocated memory, SQL statement count), `raw.csv`,
`environment.txt` (machine, versions, PostgreSQL settings, dataset size), the SQL each strategy sends, and PNG charts.
`EXPLAIN ANALYZE` plans are captured with PostgreSQL's `auto_explain` where the numbers need explaining.

## Method

- **Data:** 100k customers, 10k products, 1M orders, ~3M order lines, generated deterministically in a Flyway
  migration (no `random()`), so every run and every reader gets the same rows.
- **Correctness first:** before measuring, every strategy must return exactly the same rows.
- **Measurement:** single thread (cost of one query strategy, not throughput under load); JIT warm-up, then warm-up
  rounds, then 30–100 measured rounds per point; scenarios interleaved with a rotating start to spread noise.
  A sample is the time the application waits: transaction, SQL, and mapping included.
- **Database:** `shared_buffers=1GB`, so the dataset stays in memory and query strategy, not disk speed, is measured.

## Limitations

One machine (laptop, Docker Desktop on Windows), one database (PostgreSQL), one data shape, single-threaded.
Absolute numbers include ~1–2 ms per call of transaction and Docker networking overhead.

## Layout

```
src/main/java/dev/blazebench/
  pagination/      keyset vs offset strategies
  collectionfetch/ collection fetch + pagination strategies
  projection/      DTO / projection / Entity View strategies
  cte/             CTE / window function strategies
src/test/java/dev/blazebench/
  */*ScenariosTest correctness: all strategies return the same result
  bench/           benchmarks, CSV output and charts
results/           measured data and charts
```
