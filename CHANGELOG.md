# Twake Sync Changelog

Twake Sync is based on [DAVx⁵ OSE](https://github.com/bitfireAT/davx5-ose). This file lists only
Twake Sync specific changes; see the upstream project for DAVx⁵ changes.

## 2026.10.19.02

First release uploaded to Google Play by CI (internal track). It ships the app changes of 2026.10.19.01:
- Keep group memberships when switching group vCards to categories
- Login: the URL and Advanced login options can be reached from the email login page
- Hide the "Keep Android Open" banner on the accounts screen

## 2026.10.19.01

### Fixed
- Keep group memberships when switching group vCards to categories
- Fix lint `MissingTranslation` errors for Twake branding default strings

### Changed
- Login: the URL and Advanced login options can be reached from the email login page
- Hide the "Keep Android Open" banner on the accounts screen
- QA environment: always pull the latest images when building

## 2026.10.15.01

### Fixed
- Keep contact groups when switching the group method to categories
- Remove address book accounts when the account is removed from Android settings
- Dismiss sync error notifications of deleted local collections
- Remove a local collection right after the collection is deleted
- Login: toolbar up arrow now goes back one page, like the system back key
- Login: back from login details closes the login when the login type page was skipped

### Changed
- Hide the storage location selector when there is a single home set
- Hide the DAVx⁵ "Help Translating" button in About > Translations
- Name the debug info archive `twake-sync-debug.zip`
- "Find help" card points to Twake support and linagora issues
- Twake Sync branding for Finnish, Frisian, Czech, Spanish and British English
- Twake QA compatible environment
- Add `verifyTwakeBranding` check for l10n brand strings

## 2026.03.24.01

First Twake Sync release, rebranded from DAVx⁵ 4.5.20:
- New app id `com.twake.android.sync`, app name, icon and homepage
- Custom user agent and APK name
- Suggest installing Twake Sync ICS
- Remove Tasks app integration and hide WebDAV and Open Source screens
