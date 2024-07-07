# Change Log
All notable changes to this project will be documented in this file. 
This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

This project uses MAJOR.MINOR.COMMIT-COUNT versioning


## Unreleased

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

