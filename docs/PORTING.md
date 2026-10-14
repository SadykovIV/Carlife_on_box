# Porting CarLife to an Android box

[Русский](ru/PORTING.md) · [Home](../README.md)

The portable part is the protocol, video/input implementation and the single-owner boot design. The board integration must be established for each firmware. Keep a known working image and runtime while developing the new port.

## 1. Record a small device profile

From an already authorized ADB connection, collect read-only information. Redirect output on the host, not inside the device shell:

```sh
adb -s "$ANDROID_SERIAL" shell getprop ro.product.board
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint
adb -s "$ANDROID_SERIAL" shell getprop ro.boot.slot_suffix
adb -s "$ANDROID_SERIAL" shell getenforce
adb -s "$ANDROID_SERIAL" shell ls /sys/class/udc
adb -s "$ANDROID_SERIAL" shell ls /config/usb_gadget
adb -s "$ANDROID_SERIAL" shell ls -l /dev/usb_accessory
adb -s "$ANDROID_SERIAL" shell dumpsys display > display.txt
adb -s "$ANDROID_SERIAL" shell dumpsys media.codec > codec.txt
adb -s "$ANDROID_SERIAL" shell dumpsys usb > usb.txt
```

Permission-denied results are evidence of the access available. Keep raw dumps private. Compare the exact firmware's init files, projection scripts and kernel USB support. Do not restart a complete firmware audit if the required facts are already known.

## 2. Establish USB ownership

Find which init service first starts the OEM AA/CarPlay process, and which process writes the gadget descriptors, UDC and routing controls. The new dispatcher must choose the saved backend **before that first projection launch**, so the OEM service and CarLife cannot compete for the same USB device.

The reference controller is in `tools/boot_native/runtime/runtime-controller.sh`. Its UDC `a600000.dwc3`, configfs layout, `chelianyi-led` board, `sd_msm6350` channel, GPIO 1132 and `cp_aa` node are reference-board facts, not portable constants. Replace them only after identifying the corresponding behavior on your device. Preserve the common non-projection initialization: on the reference firmware, omitting `vendor.qcom.main.start=1` also prevented cellular services from starting.

The head unit is the AOA host. The box must expose a supported discovery function, accept the host's AOA requests and provide the accessory endpoints. The observer records a genuine kernel `ACCESSORY=START`; seeing a device node or starting a timer is not proof of a working session. Check detach/reattach, partial reads/writes and lifetime of the open accessory descriptor.

## 3. Keep the video and input contracts

Build common MD sources together with the native overlay. The path is display mirror → EGL → MediaCodec H.264 → CarLife. The reference display mirror and input injection require privileged/hidden Android APIs. A normal app using MediaProjection is a different deployment model, including its consent/lifecycle rules; do not assume the existing privileged code works as an ordinary APK.

Validate the real encoder's SPS/PPS, IDR startup, dimensions, level, PTS and repeated static picture. Retain RESET fencing, one source owner, bounded queues and error reporting. The CSD guard protects a known OEM codec path; replacing its hash without investigating a different library is not a port.

Video size, crop/rotation and input coordinates must describe the same geometry. Android density (DPI) controls UI size; it is not a substitute for correct coordinate mapping. Preserve asynchronous input. Parse supported multi-pointer formats without treating repeated legacy single-coordinate messages as independent second-finger coordinates.

## 4. Integrate early boot and saved selection

The reference flow is:

1. Provision versioned runtime and selector data after `/data` is ready; publish readiness last.
2. The early dispatcher reads the saved selection before the OEM projection process starts.
3. AA/CarPlay execute the original OEM path; CarLife requests the native init service.
4. ART-dependent services run in the default APEX namespace when it is ready.
5. One controller owns USB and manages native reconnect.

Magisk can carry a reviewed early injection, but a late `service.sh` that starts AA first and then takes over USB does not provide this ordering. A system or boot integration must preserve the target's verified-boot and signing requirements.

The Settings UI and any hardware shortcut should use the same saved selection. The reference OEM UI uses `AT#CS1` / `AT#CS2` and `cp_aa` for stock modes. These are OEM details. `direct_switch_type=3` is wireless CarPlay in that firmware, not CarLife. The current public code does not implement a three-mode hardware RESET cycle.

An OEM APK replacement needs a compatible signature and shared-UID constraints. If the required signing route is unavailable, integrate a supported UI/service rather than installing an APK with an unrelated key. No signing keys or OEM APKs are distributed here.

## 5. Validate permissions and recovery

Provide the actual access needed for configfs, the accessory device, display capture and input injection. The reference box was already permissive; that is not evidence that this service layout works under enforcing SELinux. Build and test the target policy instead of disabling protection.

Before a USB transition, verify an independent way to regain control, such as functioning Wi-Fi ADB or a tested external screen/head unit. Keep the original boot/system inputs and a rollback that matches the runtime resolver. Reliable power is part of the test: repeated brownouts can look like a protocol or cold-start failure.

## 6. Accept the port in stages

- Offline source tests and build.
- Privileged MD capture and input on the target.
- A direct USB CarLife session with the real launcher, an application and its keyboard.
- Warm reboot, then full power removal with the head unit already waiting.
- Physical reconnect, logical RESET, normal app use and a long session.
- Saved CarLife/AA/CarPlay choices, Home/Back, cellular settings and mobile Internet.
- A concrete return to the prior configuration.

Record actual video duration and errors. Keep an offline or mock PASS distinct from hardware acceptance. Test the final integrated firmware separately from a runtime installed through a different boot mechanism.

## References

- [Android Open Accessory](https://source.android.com/docs/core/interaction/accessories/aoa)
- [MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Implementing SELinux](https://source.android.com/docs/security/features/selinux/implement)
- [Android Verified Boot](https://source.android.com/docs/security/features/verifiedboot/avb)
- [HeadSwap](https://github.com/YegorkaPomidorka/headswap)
