# Changelog

## [0.17.0](https://github.com/pixelweb-org/granulee/compare/v0.16.0...v0.17.0) (2026-11-21)


### Features

* compare codecs in a trial and name the best one per column ([4e8628d](https://github.com/pixelweb-org/granulee/commit/4e8628dcc0131d29971217d9191b65b1d2070dde))

## [0.16.0](https://github.com/pixelweb-org/granulee/compare/v0.15.0...v0.16.0) (2026-11-21)


### Features

* split the trial report into tabs and draw the replayed queries ([9619222](https://github.com/pixelweb-org/granulee/commit/96192228921bb4aa713fbfcd508bdf8a5cc76937))

## [0.15.0](https://github.com/pixelweb-org/granulee/compare/v0.14.0...v0.15.0) (2026-11-21)


### Features

* say why untouched columns change size when a trial changes the key ([00ca7e8](https://github.com/pixelweb-org/granulee/commit/00ca7e8cb8daf5f56d5c227964c6d123329b4c93))


### Bug Fixes

* wrap a long table name in the page header on narrow screens ([00ca7e8](https://github.com/pixelweb-org/granulee/commit/00ca7e8cb8daf5f56d5c227964c6d123329b4c93))

## [0.14.0](https://github.com/pixelweb-org/granulee/compare/v0.13.0...v0.14.0) (2026-11-21)


### Features

* show how far a trial's query runs spread before naming a winner ([1af99f5](https://github.com/pixelweb-org/granulee/commit/1af99f5d821323bfba18832afcf9006804bc51cb))

## [0.13.0](https://github.com/pixelweb-org/granulee/compare/v0.12.1...v0.13.0) (2026-11-21)


### Features

* measure replayed queries with EXPLAIN ANALYZE in a trial ([0e3bc45](https://github.com/pixelweb-org/granulee/commit/0e3bc45414e3f989bf4d5b1c7db224b8b426a0de))

## [0.12.1](https://github.com/pixelweb-org/granulee/compare/v0.12.0...v0.12.1) (2026-11-21)


### Bug Fixes

* look up unqualified table names in the database the query ran in ([c7326e5](https://github.com/pixelweb-org/granulee/commit/c7326e564002fa926cdb91f392c7d26fc16fb22d))

## [0.12.0](https://github.com/pixelweb-org/granulee/compare/v0.11.0...v0.12.0) (2026-11-21)


### Features

* let ClickHouse say what a logged query filters on ([14d92e6](https://github.com/pixelweb-org/granulee/commit/14d92e62dda4c857d5c4219eb5fb156dc8fb5f5f))

## [0.11.0](https://github.com/pixelweb-org/granulee/compare/v0.10.0...v0.11.0) (2026-11-21)


### Features

* estimate candidate skip indexes with EXPLAIN WHATIF ([63e1e99](https://github.com/pixelweb-org/granulee/commit/63e1e996126e594bb1f4057bdbdb2aee548458d0))
* show how the primary key and the partition key are used ([63e1e99](https://github.com/pixelweb-org/granulee/commit/63e1e996126e594bb1f4057bdbdb2aee548458d0))


### Bug Fixes

* stop showing the Statistics plan step as a skip index ([63e1e99](https://github.com/pixelweb-org/granulee/commit/63e1e996126e594bb1f4057bdbdb2aee548458d0))

## [0.10.0](https://github.com/pixelweb-org/granulee/compare/v0.9.0...v0.10.0) (2026-11-21)


### Features

* show indexes and projections with the queries that use them ([efd3a31](https://github.com/pixelweb-org/granulee/commit/efd3a31e6359042492fb699854992b671bb1ff91))


### Bug Fixes

* keep a table's projections in the rebuild script ([c15400d](https://github.com/pixelweb-org/granulee/commit/c15400dcd5735b27c7f83a3bdfaa6ce91c501efa))


### Build System

* add a Makefile for local development ([666185c](https://github.com/pixelweb-org/granulee/commit/666185c7267783d118502cf79a849dce9bdf078b))

## [0.9.0](https://github.com/pixelweb-org/granulee/compare/v0.8.1...v0.9.0) (2026-11-20)


### Features

* say why a source has no query log and how to switch it on ([2cc3ba7](https://github.com/pixelweb-org/granulee/commit/2cc3ba7b2a3600cad37e2fc47af34a94c6cd2cfd))

## [0.8.1](https://github.com/pixelweb-org/granulee/compare/v0.8.0...v0.8.1) (2026-11-20)


### Bug Fixes

* count a filled-in skip index as part of the candidate ([97b2a8c](https://github.com/pixelweb-org/granulee/commit/97b2a8c61454c3c0747e4a505a419d43a305f94a))
* stop browsers from keeping an old frontend after a release ([2fcba98](https://github.com/pixelweb-org/granulee/commit/2fcba9817b2c8aa7765bb6eaf960d6eb8ac613a1))

## [0.8.0](https://github.com/pixelweb-org/granulee/compare/v0.7.0...v0.8.0) (2026-11-20)


### Features

* plan queries with EXPLAIN, test new skip indexes in trials and measure existing ones ([c1a8268](https://github.com/pixelweb-org/granulee/commit/c1a82686b03e9be08c2e3c747afc45b37b8c0271))

## [0.7.0](https://github.com/pixelweb-org/granulee/compare/v0.6.0...v0.7.0) (2026-11-20)


### Features

* Estilo look, table tabs, recommendations overview and compact trial setup ([e00e5b4](https://github.com/pixelweb-org/granulee/commit/e00e5b4714492e82c4a27a9f9c4f73f811991ef7))

## [0.6.0](https://github.com/pixelweb-org/granulee/compare/v0.5.0...v0.6.0) (2026-11-20)


### Features

* count queries on a Buffer table for the table behind it ([66ad59f](https://github.com/pixelweb-org/granulee/commit/66ad59fc0cc27d9b49bc64095e039f347cead624))

## [0.5.0](https://github.com/pixelweb-org/granulee/compare/v0.4.0...v0.5.0) (2026-11-20)


### Features

* run OPTIMIZE FINAL after applying a trial and follow its state ([e3035c9](https://github.com/pixelweb-org/granulee/commit/e3035c9937ad7d19d420f43962187809d336b6f3))
* usage page per table from system.query_log ([c8fe549](https://github.com/pixelweb-org/granulee/commit/c8fe5492c2a1e4d4f49706d8b4325c6be7641717))

## [0.4.0](https://github.com/pixelweb-org/granulee/compare/v0.3.1...v0.4.0) (2026-11-20)


### Features

* show how many files a table is on disk ([760bd56](https://github.com/pixelweb-org/granulee/commit/760bd56783b971835c962d19dac5bbb01e171745))

## [0.3.1](https://github.com/pixelweb-org/granulee/compare/v0.3.0...v0.3.1) (2026-11-19)


### Bug Fixes

* copy JSON tables into compact parts and merge them wide once ([3ff0b31](https://github.com/pixelweb-org/granulee/commit/3ff0b314b46e83e7a9e58446b403796c74e78eba))

## [0.3.0](https://github.com/pixelweb-org/granulee/compare/v0.2.3...v0.3.0) (2026-11-19)


### Features

* pick codec, compression and level per column in the trial setup ([5b826cf](https://github.com/pixelweb-org/granulee/commit/5b826cf576222fa6f882b439dbd2067267dfe896))
* review and run a trial's ALTER statements from Apply to production ([11295e3](https://github.com/pixelweb-org/granulee/commit/11295e333e25ed3c7b229d3f787732d1932dd8b6))

## [0.2.3](https://github.com/pixelweb-org/granulee/compare/v0.2.2...v0.2.3) (2026-11-19)


### Bug Fixes

* run trials on JSON, Dynamic and Variant tables with few threads ([df0fc93](https://github.com/pixelweb-org/granulee/commit/df0fc9340b7388d37ec1f71b5282168186ba2336))

## [0.2.2](https://github.com/pixelweb-org/granulee/compare/v0.2.1...v0.2.2) (2026-11-19)


### Bug Fixes

* sample every n-th row per part instead of filtering on rand() ([0dfd346](https://github.com/pixelweb-org/granulee/commit/0dfd346b7bc20d119cb8543d89c027cf80a39fee))

## [0.2.1](https://github.com/pixelweb-org/granulee/compare/v0.2.0...v0.2.1) (2026-11-19)


### Bug Fixes

* keep parentheses on skip indexes over several expressions ([874426a](https://github.com/pixelweb-org/granulee/commit/874426a540dd2d0b48e721400b9990b705129136))

## [0.2.0](https://github.com/pixelweb-org/granulee/compare/v0.1.0...v0.2.0) (2026-11-19)


### Features

* Neon Volt look ([5031736](https://github.com/pixelweb-org/granulee/commit/5031736c0db04b5e0886a15570a21322b6c01827))

## 0.1.0 (2026-11-19)


### Features

* container image, health endpoint and release pipeline ([332f276](https://github.com/pixelweb-org/granulee/commit/332f276e49a6c3a70aebdefa4c7caf11dd3402b6))


### Miscellaneous

* start versioning at 0.1.0 ([da976b9](https://github.com/pixelweb-org/granulee/commit/da976b9d3a2cf5d69f6e5e0e86ce1be5a5bccd36))
