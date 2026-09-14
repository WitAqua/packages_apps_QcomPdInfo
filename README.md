# Qualcomm PD Info

What the charger offered, and what the phone took. Reads the USB Power Delivery
state the kernel already knows and says it in words:

```
ポート port0
  契約            確立済み
  電源ロール      受電 (Sink)
  プロトコル      PD

充電器が提示した電力
  オブジェクト 1  固定 5V 3.0A
  オブジェクト 2  固定 9V 3.0A
  ...
  オブジェクト 6  PPS 3.3〜20V 3.25A
  充電器の申告: 電源から給電、給電・受電の両対応。

使用中
  要求            オブジェクト 6、16.02V・最大 3.25A
```

Read-only throughout. It reports a negotiation; it does not take part in one.

Two builds come out of this tree, differing only in how a file in `/sys` may be
opened:

| | `QcomPdInfo` | `QcomPdInfoRoot` |
| --- | --- | --- |
| Ships | inside a ROM | sideloaded onto someone else's |
| Signature | platform | ordinary |
| Shared user id | `android.uid.system` | none |
| Application id | `org.witaqua.qcom.pd_info` | `org.witaqua.qcom.pd_info.root` |
| Reads by | opening the files | a root shell |
| Which object is in use | only where the kernel publishes it | always |

Neither the kernel interface nor the way in is chosen at build time. Both
follow what can actually be read - see `core/src/.../source/Sources.kt`.

For the sideloaded build, packaged as a module for KernelSU or Magisk:
[Qcom-PD-Info-Module](https://github.com/WitAqua-tools/Qcom-PD-Info-Module).

## Building it into a ROM

### 1. Get the tree

Add it to your manifest, or to a local manifest:

```xml
<project name="packages_apps_QcomPdInfo"
         path="packages/apps/QcomPdInfo"
         remote="witaqua"
         revision="main" />
```

### 2. Build the app

`QcomPdInfo` is the ROM variant. Add it to the device makefile:

```make
PRODUCT_PACKAGES += \
    QcomPdInfo
```

It needs nothing else: `androidx.appcompat` and `androidx.preference` come from
the tree's own prebuilts, and there is no dependency on SettingsLib.

### 3. Label the nodes for it

This is the part that is actually device work. `PdInfo` runs as `system_app`,
and on a stock policy that domain cannot read any of what it wants. Which
labels matter depends on the interface the board has - see
[docs/kernel.md](docs/kernel.md) for which one that is.

For Qualcomm's own driver, the objects sit under the power delivery PHY's
platform device. Label the subtree and grant the read, in your device tree:

```
# sepolicy/vendor/file.te
type sysfs_usbpd, sysfs_type, fs_type;
```

```
# sepolicy/vendor/genfs_contexts
# The path is <spmi controller>:<pmic node>:<child node>, which follows the
# device tree. Read it out of your own dtsi rather than copying this one.
genfscon sysfs /devices/platform/soc/<...>/usbpd   u:object_r:sysfs_usbpd:s0
```

```
# sepolicy/vendor/system_app.te
allow system_app sysfs_usbpd:dir r_dir_perms;
allow system_app sysfs_usbpd:file r_file_perms;
```

For the upstream class, the type-C class and the charger-facing power supply are
usually already labelled by the vendor policy; what is missing is the grant:

```
# sepolicy/vendor/system_app.te
allow system_app vendor_sysfs_usb_c:dir r_dir_perms;
allow system_app vendor_sysfs_usb_c:file r_file_perms;
allow system_app vendor_sysfs_usb_supply:file r_file_perms;
```

One more read is worth granting on any qualcomm board, whichever interface it
has: `/sys/class/qcom-battery/usb_real_type`, which is the only thing that says
whether a contract is against a programmable supply - see
[docs/kernel.md](docs/kernel.md). Its label is the charger's, and vendor
policies differ on what that is.

Check the labels on the handset rather than trusting any of these lists:

```sh
adb shell su -c 'ls -Zd /sys/class/usbpd /sys/class/usb_power_delivery /sys/class/typec'
adb shell su -c 'ls -Z /sys/class/power_supply/*/voltage_now /sys/class/qcom-battery/usb_real_type'
```

`logcat | grep avc` while the screen is open will name anything still refused.

### 4. What you get

With the nodes readable, `QcomPdInfo` needs no root and no debugfs. On a board
with Qualcomm's driver that is the whole of it. On one with the upstream class
whose firmware does not report that it can list its objects, the list will not
appear however the policy is written - the kernel never read it. That case, and
the one-line kernel change that fixes it, are in
[docs/kernel.md](docs/kernel.md).

On a board with neither interface - an older kernel whose power delivery lives
in the charger firmware - there is no list to label anything for, and what comes
out is the contract UCSI worked out from objects it will not hand over. That
case is [docs/5.10_xiaomi-sm8450.md](docs/5.10_xiaomi-sm8450.md).

## Building the sideloaded variant

Soong is the primary build - the ROM variant wants the platform signature and
the system shared user id, which only a build inside an android tree can give
it. `QcomPdInfoRoot` can also be built with Gradle, which is what CI does, since a
runner has no tree:

```sh
gradle assembleRelease
```

It comes out unsigned unless `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` are in the environment. The key lives in
[WitAqua-tools/Android-Keys](https://github.com/WitAqua-tools/Android-Keys),
and the build that gets published is made by
[Qcom-PD-Info-Module](https://github.com/WitAqua-tools/Qcom-PD-Info-Module)'s release
workflow rather than by hand.

`gradle/AndroidManifest.xml` is the sideloaded variant's manifest without the
package attribute, which AGP 8 refuses because it takes the application id from
`build.gradle.kts`. Soong reads it from the manifest and has nowhere else to
look, so the two cannot share a file - keep them in step.

## Documentation

- [docs/kernel.md](docs/kernel.md) - which interface a kernel publishes, what
  each one carries, why a platform can register the upstream class and leave it
  empty, and the kernel change that fixes that.
- [docs/5.10_xiaomi-sm8450.md](docs/5.10_xiaomi-sm8450.md) - a board with none
  of the three interfaces at all: why, what is left to read, and what it would
  take to get the rest.

## Licence

Apache 2.0.
