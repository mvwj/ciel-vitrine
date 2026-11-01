# Documentation

The main [README](../README.md) and [Russian overview](../README.ru.md) describe
the current project. Protocol specifications explain formats; validation
reports record checks actually run. Older milestone reports retain their
original scope and are not statements about the current version.

## Start here

- [Architecture and module map](ARCHITECTURE.md)
- [Yandex OAuth setup](YANDEX_SETUP_RU.md)
- [Requirements and development plan](DEVELOPMENT_PLAN_RU.md)
- [Upload badges, cold archive and local removal](CLOUD_STATUS_AND_LOCAL_RELEASE_RU.md)
- [Contributing and test precautions](../CONTRIBUTING.md)
- [Security model and private reports](../SECURITY.md)
- [Public source preparation](PUBLICATION.md)

## Formats and viewing

- [CVPOS3: original bytes and source dates](CVPOS3_SPEC.md)
- [CVPOS2: container and independent recovery](CVPOS2_SPEC.md)
- [Legacy POS v1](POS_V1_SPEC.md)
- [Partial cloud photo viewing](PARTIAL_CLOUD_VIEW_RU.md)
- [Video and large originals](VIDEO_AND_LARGE_ORIGINALS_RU.md)
- [Independent Python decoder](../pos-reference-decoder/python/ciel_pos2_decoder.py)

## Validation

- [0.1.13 transfer controls, container suffixes and cloud photo zoom](VALIDATION_0_1_13_RU.md)
- [0.1.11 cloud status, two-copy workflow and Android consent](VALIDATION_0_1_11_RU.md)
- [0.1.10 integration and measured limits](INTEGRATION_2026_10_30_RU.md) · [structured results](fixtures/validation-0.1.10.json)
- [0.1.9 partial cloud viewing and measurements](VALIDATION_0_1_9_RU.md)
- [0.1.8 theme, timeline and restoration bindings](VALIDATION_0_1_8_RU.md)
- [0.1.7 media safety and native dates](VALIDATION_0_1_7_RU.md)
- [0.1.6 timeline and real cloud recovery](VALIDATION_0_1_6_RU.md)
- [0.1.5 ten-photo recovery](VALIDATION_0_1_5_RU.md)
- Earlier milestones: [0.1.4](VALIDATION_0_1_4_RU.md), [0.1.3](VALIDATION_0_1_3_RU.md),
  [0.1.2](VALIDATION_0_1_2_RU.md), [0.1.1](VALIDATION_0_1_1_RU.md), [0.1](VALIDATION_0_1_RU.md)

Old CI links may refer to the private development repository retained during
publication cleanup. The reports keep aggregate measurements, with device
identifiers and private configuration removed.

## Historical design

- [v26 audit](../gallery_architecture_v26_audit_ru.md)
- [v29 working specification](SPEC_V29.md)

Historical requirements are not proof of implemented behavior. Automatic Live
Photo completeness, catalog recovery after reinstall and cloud trash remain
separate unfinished tasks.
