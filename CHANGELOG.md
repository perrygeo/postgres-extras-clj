# Change Log
All notable changes to this project will be documented in this file. 
This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

This project uses MAJOR.MINOR.COMMIT-COUNT versioning

## Unreleased

- Use a fixture to manage postgres database in tests.

## [0.1.21] - 2024-07-21

- FIX: For the index-cache-hit and table-cache-hit tables, COALESCE null values to zero for unused tables.
- CHANGES: Added pgbench example notebook.

## [0.1.17] - 2024-07-08

- Declare fns before hugsql macros to get proper docstrings

## [0.1.15] - 2024-07-08

### Changes

- Remove the outlier-legacy and calls-legacy functions, which served no purpose that I could tell.
- Changed the outliers and calls return maps to use :exec_time_ms (bigdecimal, explicit units) instead of :exec_time.
- Similarly, changed the outliers and calls return maps with :sync_io_time_ms

## [0.1.12] - 2024-07-08

### Fix

- vacuum cols should be numeric, not string

### Changes

- remove healthcheck from read-stats, doesn't fit the record structure.

## [0.1.10] - 2024-07-07

### Fix

- Add :indexes to data dictionary map
- Declare hugsql functions to make names explicit; clj_kondo is happy

## [0.1.8] - 2024-07-07

### Fix

- Respect :limits for all stats

### Changes

- Change diagnostic keyword from :desc to :onfalse for clarity

## [0.1.5] - 2024-07-06

### Fixed

- Correct :call
- Bug fix :null-indexes logic

## [0.1.4] - 2024-07-06

- Initial release.

