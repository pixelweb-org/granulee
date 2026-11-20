# Changelog

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
