# يُسر - جامعة قنا (الكادر الإداري وهيئة التدريس)

Android WebView application for the official Qena University Yousr portal, dedicated to Administrative Staff and Faculty Members.

- Portal: https://mispg.qu.edu.eg/yousr/
- Application ID: `eg.qu.edu.yousr`
- Arabic RTL website content is rendered from the official portal.
- JavaScript, DOM storage, cookies, file chooser, downloads, external links, and Android back navigation are supported.

## Build using GitHub Actions

1. Create a new **Public** or **Private** repository in your GitHub account.
2. Upload the complete contents of this folder to the repository root.
3. Open **Actions** and enable workflows if GitHub asks.
4. Push to `main`, or choose **Android Build** → **Run workflow**.
5. Open the completed workflow run and download the artifact. It contains a debug APK for testing and an AAB release bundle.

## Important before Google Play publication

- The AAB generated here is an unsigned release bundle. Google Play publication requires a properly signed AAB and Play App Signing configuration.
- Replace the temporary launcher icon with the university-approved official app icon and add the approved splash screen/branding.
- Test login, session persistence, file upload/download, password reset, and all portal links on real Android devices.
- WebView behavior depends partly on the portal's server configuration and authentication flow.
- This starter does not collect credentials or send portal data to any third party.