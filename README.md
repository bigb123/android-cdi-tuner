# Android application for API Tech CDI device in Honda Sonic

The idea of the application is to support displaying useful information about Coil Discharge Ignition device in the motorbike. Adjusting spark timing from within the app is also supported.

CDI is responsible for triggering a spark in the cylinder. We can tune ignition timing.

Check releases to learn more about currently supported functionality.

<img width="300" alt="gauges with speedo" src="https://github.com/user-attachments/assets/48a715f0-3d72-4ee4-bb45-e40c63ce32d7" />
<img width="300" alt="dark gauges with speedo" src="https://github.com/user-attachments/assets/2196fedd-d60b-47d2-9172-2a40fed94210" />

https://github.com/user-attachments/assets/7867c8fc-947a-4bac-b5ef-2deb2eb86cf9

## Bluetooth requirements

Make sure the device is already added to Bluetooth devices. It has to be on the list of Android's known devices. It doesn't have to be connected.

## Permissions required

- Bluetooth access - for connectivity with CDI over Bluetooth (it requires external Bluetooth device connected to CDI as CDI itself doesn't have Bluetooth)
- GPS - for speed gauge

## Disclaimer - AI was used to build this project

Claude Opus 4.1, 4.5 and 4.6 was used to make the project real.

## Special thanks

Thanks to [SimpleBluetoothTerminal](https://github.com/kai-morich/SimpleBluetoothTerminal) for inspiration on how to handle Bluetooth devices choice menu!
