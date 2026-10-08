# SambaLite



## What's New — SMBv1 (CIFS/NT1) Legacy Support

This fork adds optional **SMBv1 support** via [jcifs-ng](https://github.com/AgNO3/jcifs-ng) for connecting to old NAS devices, Windows XP/Server 2003, and embedded systems that do not support SMB2/3.

- **Explicit per-connection toggle** — a "Use Legacy SMBv1 (CIFS)" switch in the Add/Edit Connection dialog. Existing connections are unaffected; the toggle defaults to off.
- **Full feature parity** — browse, upload, download, delete, rename, create folder, search, folder sync, and transfer queue all work over SMBv1.
- **Zero impact on SMB2/3 paths** — the SMBJ code is completely unchanged. The jcifs-ng backend is only invoked when the toggle is on.
- **Powered by** `eu.agno3.jcifs:jcifs-ng:2.1.9` alongside the existing `com.hierynomus:smbj:0.14.0`.

> **Note:** SMBv1 has known security weaknesses (no encryption, no modern signing). Use it only on trusted local networks and only when the server cannot be upgraded to SMB2/3.

---

SambaLite is a lightweight, modern, and open-source Android client for SMB/CIFS shares (Samba). It provides a minimalistic, reliable, and secure tool for accessing SMB shares on local networks without unnecessary features, ads, or bloat.

**Note:** SambaLite is an independent open-source project and is not affiliated with the official [Samba Project](https://www.samba.org/) or SerNet.  
The name refers solely to the supported SMB/CIFS network protocols.





## Features

| Feature                | Status | Description                                     |
| ---------------------- | ------ | ----------------------------------------------- |
| SMB/Share Connection   | ✅     | Connect with username/password and domain       |
| Legacy SMBv1 (CIFS)    | ✅     | Optional per-connection toggle for old NAS/devices that don't support SMB2/3 |
| File Browsing          | ✅     | Navigate through folders and files              |
| Download/Upload        | ✅     | Transfer files between device and share         |
| Open Files             | ✅     | Open files directly from the share              |
| Share Files/Text       | ✅     | Share files and text to SMB shares              |
| Delete/Rename          | ✅     | Basic file operations with confirmation         |
| Search with Wildcards  | ✅     | Find files using * and ? wildcards              |
| Modern UI              | ✅     | Material Design with Dark Mode support          |
| Multiple Connections   | ✅     | Manage multiple shares with custom names        |
| Security/Privacy       | ✅     | Encrypted credential storage, no telemetry      |
| Folder Sync            | ✅     | Automatic background sync between device and share ([User Guide](docs/sync_user_guide.md)) |
| Transfer Queue         | ✅     | Background queuing for uploads and downloads ([User Guide](docs/transfer_queue_user_guide.md)) |

## Mobile Document Pipeline

SambaLite can be combined with
[MakeACopy](https://github.com/egdels/makeacopy) to create a fully
automated document workflow:

    Paper document
        ↓
    Scan with MakeACopy
        ↓
    Inbox folder
        ↓
    SambaLite folder sync
        ↓
    NAS / archive (e.g. paperless-ngx)

This effectively turns your smartphone into a **privacy-friendly mobile network scanner**.

## Project Goals

- **Minimalism:** Only essential features, no unnecessary configurations
- **User-Friendly:** Clean, intuitive interface with minimal UI
- **Modern:** Using current Android libraries and best practices
- **Privacy-Focused:** No telemetry, tracking, or unnecessary permissions
- **Maintainable:** Clean, documented code that's easy to extend

## Technical Details

- **Language:** Java 11+
- **Architecture:** MVVM with Repository pattern
- **Dependencies:**
  - AndroidX and Material Design components
  - Dagger 2 for dependency injection
  - SMBJ for SMB2/3 client functionality
  - jcifs-ng for optional SMBv1 (CIFS/NT1) legacy support
  - EncryptedSharedPreferences for secure credential storage

## Building the Project

### Requirements

- **JDK 21** (the project sets `sourceCompatibility = VERSION_21`)
- Android SDK with `compileSdk 36`

On Ubuntu/Debian, install JDK 21 if not already present:
```bash
sudo apt-get install -y openjdk-21-jdk-headless
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

### Android Studio

1. Clone the repository
2. Open the project in Android Studio
3. Build and run on your device or emulator (`Run > Run 'app'`)

### Command Line

```bash
# Clone
git clone https://github.com/egdels/SambaLite.git
cd SambaLite

# Debug APK (installs directly on a device)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk

# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Release APK

A signed release build requires the keystore credentials as environment variables or Gradle properties:

```bash
export SIGNING_STORE_PASSWORD=<password>
export SIGNING_KEY_ALIAS=<alias>
export SIGNING_KEY_PASSWORD=<password>

./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

Unsigned release builds (e.g. for F-Droid) are produced automatically when the signing variables are absent.

## Security Notes

- Credentials are stored securely using Android's Keystore system
- No unencrypted storage of sensitive data
- Minimal permissions required (only network and user-selected storage access)
- Be cautious when using SMB in public or untrusted networks

**Privacy notice:** SambaLite processes all data locally on your device.  
No personal data is transmitted to the developer or any third parties.



## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

## Third-Party Libraries

This project uses the SMBJ library (com.hierynomus:smbj), version 0.14.0, for SMB2/3 client functionality.

SMBJ is licensed under the Apache License, Version 2.0.
For more information, see: https://github.com/hierynomus/smbj

Copyright (c) 2016-2024 Michael N. Heronimus

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

This project also uses the jcifs-ng library (eu.agno3.jcifs:jcifs-ng), version 2.1.9, for optional SMBv1 (CIFS/NT1) legacy support.

jcifs-ng is licensed under the GNU Lesser General Public License, Version 2.1.
For more information, see: https://github.com/AgNO3/jcifs-ng

## Disclaimer / Limitation of Liability

This software is provided "as is", without warranty of any kind, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose, and noninfringement. In no event shall the authors or copyright holders be liable for any claim, damages, or other liability, whether in an action of contract, tort, or otherwise, arising from, out of, or in connection with the software or the use or other dealings in the software.

Where not permitted by applicable law (e.g. in cases of gross negligence or intent), this limitation of liability may not apply. Users utilize this software at their own risk.

## Privacy Policy

Our privacy policy is available on our [GitHub Pages site](https://egdels.github.io/SambaLite/privacy_policy.html).

## Related Projects

### MakeACopy

[MakeACopy](https://github.com/egdels/makeacopy) is a privacy-friendly
open-source document scanner for Android with offline OCR.

Together with SambaLite it enables a fully automated document workflow:

    Paper
       ↓
    Scan with MakeACopy
       ↓
    Inbox folder
       ↓
    SambaLite sync
       ↓
    NAS / archive

This setup effectively turns your smartphone into a **mobile network
scanner** for self-hosted document archives such as **paperless-ngx**.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request
