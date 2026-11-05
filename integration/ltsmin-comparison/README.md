# Archived reference-backend build utility

`build-reference.py` records the compatibility path used to retain the original
Sylvan1.1.2/BuDDy2.4 adapters and library identities on the pinned LTSmin
07f9bf8 frontend. Its HWLOC wrapper restricts binding before discovery, rather
than relying on taskset alone. This is benchmark-environment wiring, not a
replacement DD kernel.

The utility requires pristine pinned LTSmin, a legacy LTSmin source tree containing
the original reference adapters, and the exact Sylvan/BuDDy/dependency prefixes.
See `python3 integration/ltsmin-comparison/build-reference.py --help` for paths.
These archived inputs are not fetched by this utility and are not supplied by
the C-NDD bridge. Do not substitute a current Sylvan release and describe it as
the same baseline.

The published [60-model snapshot](../../results/model_checking_20261105/README.md)
provides results, parameters and hashes. This directory intentionally does not
include the old three-model configuration or a server-path-dependent full-matrix
runner. New measurements require a separate frozen model list, semantic gates,
resource monitoring and identity manifest; plotting the checked-in results does
not require these archived build inputs.
