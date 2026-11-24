# ProjectorHome

Лёгкий домашний экран для Android-проекторов и Android TV-приставок.

> **Статус совместимости:** проект изначально разрабатывался для SHAKACHU C50 на Android 9 (API 28). Работа на других моделях, прошивках и разрешениях пока не подтверждена. Перед установкой на другое устройство обязательно проверьте возможность вернуть штатный лаунчер.

## Возможности

- Ландшафтный домашний экран
- Часы и дата
- Плитки установленных приложений
- Управление с пульта: стрелки и OK
- Избранные приложения в доке
- Выбор обоев
- Погода с определением местоположения устройства
- Быстрый доступ к настройкам Android
- Нет сторонних runtime-библиотек и постоянно работающего фонового сервиса

## Требования

- Android 9 (API 28) или новее
- Экран с ландшафтной ориентацией
- Пульт или другое устройство ввода с навигацией по фокусу

Минимальная версия Android в сборке: API 28. Это техническое требование APK, а не гарантия совместимости с каждой прошивкой.

## Скачать APK

**Готовые стабильные версии:** откройте [Releases](https://github.com/andrytsunami/C50-launcher/releases) и скачайте `ProjectorHome.apk` в разделе **Assets** последнего релиза.

**Тестовые сборки:** доступны во вкладке **Actions** → успешный запуск **Build ProjectorHome** → **Artifacts**. Артефакты тестовых сборок хранятся 30 дней.

Обычные изменения в `main` и pull request запускают тестовую сборку автоматически. Стабильный релиз публикуется отдельно через workflow **Publish ProjectorHome Release**:
1. Откройте **Actions** → **Publish ProjectorHome Release** → **Run workflow**.
2. Укажите номер версии в формате `MAJOR.MINOR.PATCH`, например `1.0.1` (без префикса `v`).
3. Запустите workflow. После успешной сборки GitHub создаст тег `v1.0.1` и Release с APK в **Assets**.

Для публикации релиза workflow использует разрешение `contents: write`. Личные GitHub Secrets для сборки не нужны.

## Установка и безопасная проверка

**Удалять предыдущую версию ProjectorHome перед обновлением не нужно.** Стабильные релизы подписываются одним постоянным ключом. Android проверяет подпись и разрешает установку новой версии поверх старой, сохраняя локальные данные приложения — выбранные обои и настройки дока.

Скачайте `ProjectorHome.apk` из Assets последнего стабильного релиза и установите его обычным способом поверх установленной версии. Если Android сообщает об ошибке подписи, не удаляйте приложение сразу: сначала убедитесь, что устанавливаете именно официальный APK из раздела Releases, а не тестовую сборку из Actions.

Тестовые сборки Actions также используют постоянный ключ, поэтому их можно устанавливать поверх предыдущих сборок этого проекта. Однако для обычного использования рекомендуется стабильный релиз.

После установки:

1. Запустите ProjectorHome вручную и проверьте открытие приложений, работу стрелок и кнопки OK.
2. Назначьте ProjectorHome домашним экраном (HOME) через ADB:
   ```bash
   adb shell cmd package set-home-activity --user 0 ru.andry.projectorlauncher/.MainActivity
   ```
3. Откройте домашний экран и проверьте, что запускается ProjectorHome:
   ```bash
   adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
   ```
4. Проверьте, какое приложение назначено HOME:
   ```bash
   adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
   adb shell settings get secure preferred_home_activity
   ```

Чтобы штатный лаунчер не запускался после перезагрузки, на SHAKACHU C50 мы отключили его пакет:

```bash
adb shell pm disable-user --user 0 com.zhiying.ytb.zy.tv
```

Чтобы снова включить штатный лаунчер:

```bash
adb shell pm enable --user 0 com.zhiying.ytb.zy.tv
```

**Важно:** отключение пакета проверено на SHAKACHU C50. Не удаляйте штатный лаунчер: команда `enable` позволяет вернуть его. На других устройствах имя пакета может отличаться.

## Известные ограничения

- Определение местоположения зависит от разрешений Android и возможностей прошивки. Если координаты получить не удаётся, приложение может использовать резервный вариант определения города.
- Наличие системных разделов (звук, приложения, настройки) зависит от производителя и прошивки. Некоторые пункты могут быть недоступны на других устройствах.
- Значки и список приложений зависят от того, какие приложения прошивка публикует как запускаемые.
- Совместимость с другими проекторами и ТВ-приставками не заявляется без тестирования.

## Сообщить об ошибке или запросить поддержку устройства

Откройте вкладку **Issues** и укажите:
- точную модель устройства;
- версию Android (если известна);
- что именно произошло;
- шаги для повторения проблемы;
- фото или скриншот ошибки (если есть).

Не публикуйте в Issues пароли, токены, серийные номера и другие личные данные.

## Сборка из исходников

Проект использует Android Gradle Plugin 8.6.1, Kotlin 2.0.21, Gradle 8.7 и JDK 17.

Выполните:

```bash
gradle --no-daemon assembleDebug
```

Готовый APK появится здесь:

```
app/build/outputs/apk/debug/app-debug.apk
```

Workflow GitHub Actions автоматически переименовывает его в `ProjectorHome.apk` и прикладывает к запуску как артефакт.

## Лицензия

Проект распространяется по лицензии [MIT](LICENSE). Вы можете использовать, копировать, изменять и распространять код при сохранении уведомления об авторских правах и текста лицензии.

---

# ProjectorHome (English)

A lightweight home screen for Android projectors and Android TV boxes.

> **Compatibility status:** The project was originally developed for the SHAKACHU C50 running Android 9 (API 28). Operation on other models, firmware versions, and screen resolutions has not yet been verified. Before installing it on another device, make sure you can restore the stock launcher.

## Features

- Landscape home screen
- Clock and date
- Installed app tiles
- Remote control navigation: D-pad and OK
- Favourite apps in the dock
- Wallpaper selection
- Weather based on the device's location
- Quick access to Android settings
- No third-party runtime libraries or continuously running background service

## Requirements

- Android 9 (API 28) or later
- Landscape display
- Remote control or another input device that supports focus navigation

The minimum Android version configured for the build is API 28. This is an APK requirement, not a guarantee of compatibility with every firmware.

## Download APK

1. Open the repository's **Actions** tab.
2. Select the latest successful **Build ProjectorHome** run.
3. Find **Artifacts** at the bottom of the run page.
4. Download the **ProjectorHome** archive and extract `ProjectorHome.apk`.

The APK is built automatically on pushes to `main`, manual workflow runs, and pull requests. No personal GitHub Secrets are required.

## Installation and safe testing

**You do not need to uninstall the previous ProjectorHome version before updating.** Stable releases are signed with the same permanent signing key. Android verifies the signature and allows the new version to be installed over the old one while preserving local app data, including the selected wallpaper and dock settings.

Download `ProjectorHome.apk` from the Assets section of the latest stable release and install it normally over the existing app. If Android reports a signature error, do not uninstall the app immediately: first make sure you are installing the official APK from Releases, not a test build from Actions.

GitHub Actions test builds also use the permanent signing key, so they can be installed over previous builds of this project. Stable releases are recommended for normal use.

After installation:

1. Launch ProjectorHome manually and test app launching, D-pad navigation, and the OK button.
2. Set ProjectorHome as the HOME app through ADB:
   ```bash
   adb shell cmd package set-home-activity --user 0 ru.andry.projectorlauncher/.MainActivity
   ```
3. Open the home screen and verify that ProjectorHome starts:
   ```bash
   adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
   ```
4. Check which activity is assigned to HOME:
   ```bash
   adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
   adb shell settings get secure preferred_home_activity
   ```

To prevent the stock launcher from starting after reboot, we disabled its package on the SHAKACHU C50:

```bash
adb shell pm disable-user --user 0 com.zhiying.ytb.zy.tv
```

To enable the stock launcher again:

```bash
adb shell pm enable --user 0 com.zhiying.ytb.zy.tv
```

**Important:** disabling this package has been tested on the SHAKACHU C50. Do not uninstall the stock launcher; the `enable` command allows you to restore it. Package names may differ on other devices.

## Known limitations

- Location detection depends on Android permissions and firmware support. If the device cannot provide coordinates, the app may use a fallback city lookup.
- System sections (sound, apps, settings) depend on the manufacturer and firmware. Some entries may be unavailable on other devices.
- App icons and the app list depend on which apps the firmware exposes as launchable.
- Compatibility with other projectors and TV boxes is not guaranteed without testing.

## Report a bug or request device support

Open the **Issues** tab and include:
- the exact device model;
- Android version, if known;
- what happened;
- steps to reproduce the issue;
- a photo or screenshot, if available.

Do not post passwords, tokens, serial numbers, or other personal information in Issues.

## Build from source

The project uses Android Gradle Plugin 8.6.1, Kotlin 2.0.21, Gradle 8.7, and JDK 17.

Run:

```bash
gradle --no-daemon assembleDebug
```

The APK will be generated at:

```
app/build/outputs/apk/debug/app-debug.apk
```

The GitHub Actions workflow renames it to `ProjectorHome.apk` and uploads it as a workflow artifact.

## License

The project is distributed under the [MIT](LICENSE) license. You may use, copy, modify, and distribute the code while retaining the copyright notice and license text.
