# Light Chat for Light Phone III

Use OpenRouter’s free chat models, or connect your own OpenAI or Gemini account. Switch models mid-chat and review the context before sending it to another provider. Light Chat supports text, image and PDF attachments, URL reading with supported models, dictation, and Gemini live voice. Availability and quota depend on the model and provider.

## Install

Download `light-chat-v0.2.1-arm64.apk` from [Releases](../../releases). Pair Tool Manager with your Light Phone III and choose the APK to install. If LightOS asks you to trust the developer, compare the signing fingerprint in the release with the one on your phone.

## Setup and privacy

Add your provider key in **Settings**. Light Chat stores keys on the device using Android Keystore-backed encryption. Your selected provider receives the request and the context or attachment you approve. Audio features identify the provider before sending audio or text for processing. Saved chats expire after 30 days by default. You can change the limit, keep chats until deleted, or start a one-off chat that stays out of local history.

## Build

This repository contains the app’s `tool` module. To build it locally, use a matching [Light SDK](https://github.com/lightphone/light-sdk) checkout (SDK version 0.2.4), replace its `tool` directory with this repository’s module, and build with JDK 17 and Android SDK 36. Run `./gradlew :tool:assembleDebug` for a debug build or `./gradlew :tool:assembleRelease` for the unsigned ARM64 release. Sign with your own keystore when distributing your own build.
