import {readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

const iconPath = fileURLToPath(new URL('../web/icons/icon-192.png', import.meta.url));
const outputPath = fileURLToPath(new URL('../web/Licht-Meter.mobileconfig', import.meta.url));
const icon = readFileSync(iconPath)
  .toString('base64')
  .match(/.{1,76}/g)
  .map(line => `                ${line}`)
  .join('\n');

const profile = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>ConsentText</key>
    <dict>
        <key>default</key>
        <string>Dit profiel voegt alleen de Licht-Meter webapp toe aan het beginscherm. Het configureert geen accounts, netwerkverbindingen of toestelbeheer.</string>
    </dict>
    <key>PayloadContent</key>
    <array>
        <dict>
            <key>FullScreen</key>
            <true/>
            <key>Icon</key>
            <data>
${icon}
            </data>
            <key>IsRemovable</key>
            <true/>
            <key>Label</key>
            <string>Licht-Meter</string>
            <key>PayloadDescription</key>
            <string>Voegt Licht-Meter met appicoon toe aan het beginscherm.</string>
            <key>PayloadDisplayName</key>
            <string>Licht-Meter Web Clip</string>
            <key>PayloadIdentifier</key>
            <string>nl.growshopsluis.lichtmeter.webclip</string>
            <key>PayloadType</key>
            <string>com.apple.webClip.managed</string>
            <key>PayloadUUID</key>
            <string>586D7826-6F8A-4894-98AA-A5EC6463F067</string>
            <key>PayloadVersion</key>
            <integer>1</integer>
            <key>Precomposed</key>
            <true/>
            <key>URL</key>
            <string>https://deus73.github.io/Licht-Meter/</string>
        </dict>
    </array>
    <key>PayloadDescription</key>
    <string>Installeert de Licht-Meter webapp met appicoon als verwijderbare snelkoppeling op het beginscherm.</string>
    <key>PayloadDisplayName</key>
    <string>Licht-Meter webapp</string>
    <key>PayloadIdentifier</key>
    <string>nl.growshopsluis.lichtmeter.profile</string>
    <key>PayloadOrganization</key>
    <string>Growshop Sluis</string>
    <key>PayloadRemovalDisallowed</key>
    <false/>
    <key>PayloadType</key>
    <string>Configuration</string>
    <key>PayloadUUID</key>
    <string>FE40D47A-0EDF-400B-BBDB-0BFF89AF584E</string>
    <key>PayloadVersion</key>
    <integer>1</integer>
</dict>
</plist>
`;

writeFileSync(outputPath, profile);
console.log(`Generated ${outputPath}`);
