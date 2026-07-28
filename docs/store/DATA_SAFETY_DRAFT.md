# Google Play Data safety: production draft for V Slot

Статус: **не отправлять в Play Console без закрытия пунктов ниже**. Черновик составлен по исходному коду и прямым runtime-зависимостям на 2026-07-28. Он не знает production API keys, dashboard settings, privacy-host/CDN logs, договорные роли провайдеров и реальные сетевые ответы release AAB.

## Фактическая конфигурация в коде

- AppMetrica Analytics `8.3.0` активируется с `withDataSendingEnabled(false)`, `withAdvIdentifiersTracking(false)`, `withLocationTracking(false)`, `withCrashReporting(false)` и `withNativeCrashReporting(false)`. Пользователь может отдельно включить аналитику и затем отозвать согласие.
- Runtime-граф AppMetrica содержит Google Play Install Referrer, а consent-текст честно называет источник установки. Пока production dashboard и network capture не докажут, что install attribution/postbacks не используются, для относящихся к этому потоку типов данных нужно учитывать purpose `Advertising or marketing` наряду с `Analytics`; отсутствие рекламы в приложении само по себе этот purpose не исключает.
- Исключены AppMetrica-модули ad revenue, advertising identifiers, App Set ID, billing, ID sync, location, native crashes и screenshots.
- Custom analytics events содержат просмотры экранов, выбор слота, gameplay/spin events, диапазоны виртуальных ставок, линий, баланса, выигрыша, бонусов, бесплатных вращений и уровня, bonus actions, настройки, результаты permission prompt и privacy-load status. Перед SDK все используемые числовые значения виртуальной экономики заменяются фиксированными диапазонами; код также фильтрует URL/email/query-like строки. Эти события всё равно являются app activity. Exported launcher не читает произвольные push extras.
- Firebase Cloud Messaging `25.1.1` и AppMetrica Push `4.3.0` используются для уведомлений. До системного `POST_NOTIFICATIONS` prompt приложение показывает русский pre-prompt, который называет Firebase (Google) и AppMetrica (Яндекс), идентификаторы приложения/устройства и телеметрию доставки уведомлений. Firebase auto-init выключен в manifest и включается только после сохранённого действия пользователя, разрешения `POST_NOTIFICATIONS` и включённых системных уведомлений. При отзыве после запроса код выключает auto-init и удаляет FCM token вместе с Firebase Installation ID; это не удаляет ранее обработанные данные провайдеров.
- Нет Firebase Analytics, ads SDK, Play Billing, аккаунтов, авторизации, location permissions, advertising ID permission, contacts/media/files/camera/microphone access.
- Игровой баланс, настройки, disclaimer acceptance и pending spin state сохраняются локально. Android backup и device transfer для этих данных выключены.
- Privacy policy открывается в ограниченном WebView только по HTTPS; navigation и subresources разрешены лишь на том же origin. Cookies, JavaScript, DOM storage, file/content access и mixed content выключены. Сервер всё равно может видеть сетевые метаданные, включая IP и User-Agent.

## Предлагаемые верхнеуровневые ответы

| Вопрос Play Console | Черновой ответ | Условие перед отправкой |
|---|---|---|
| Приложение собирает или передаёт user data? | **Yes** | AppMetrica telemetry и Firebase/AppMetrica push передают данные с устройства при opt-in. SDK-сбор входит в форму Google Play. |
| Все собираемые данные шифруются при передаче? | **Yes, только после проверки** | Firebase документирует шифрование in transit, приложение запрещает cleartext и privacy page использует HTTPS. Нужен network audit release AAB и письменное подтверждение применимой AppMetrica transport-конфигурации. Если хоть один поток не защищён, ответить `No`. |
| Пользователь может запросить удаление данных? | **No в текущем production scope** | В приложении нет аккаунтов, но revoke останавливает будущую аналитику и не является запросом удаления ранее собранных SDK-данных. Ответ можно изменить на `Yes` только после запуска глобально доступного email/form/in-app процесса с подтверждённым удалением у AppMetrica/Firebase либо документированной автоматической anonymization/deletion в пределах правил Play. |

Google считает передачу данных SDK-серверам сбором, а pseudonymous identifiers нужно декларировать. Опциональным сбор можно считать, только если все пользователи во всех регионах могут использовать приложение без него: [Google Play Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).

## Предлагаемые data types

`Shared: No*` ниже допустимо только после договорной проверки сноски.

| Категория / data type | Collected | Shared | Required or optional | Purposes | Фактический источник |
|---|---:|---:|---|---|---|
| App activity / App interactions | Yes | No* | Optional | Analytics | Открытия приложения/экранов, выбор слота, paytable/privacy/settings и permission interactions. Отдельный custom `push_open` из launcher extras удалён; фактическую SDK push telemetry проверить по network capture/dashboard. |
| App activity / Other actions | Yes | No* | Optional | Analytics | Spin start/result, виртуальные stake/lines/win/balance, free-spin/autospin и daily bonus events. Это gameplay без денежной ценности. |
| App info and performance / Crash logs | **No** | — | — | — | Java- и native-crash reporting явно выключены, native-crash module исключён, ручных crash-report вызовов нет. Любое включение требует повторного consent/Data safety review. |
| App info and performance / Diagnostics | Yes | No* | Optional | Analytics | Техническую диагностику AppMetrica без crash reports подтвердить по production network capture и dashboard до отправки. |
| App info and performance / Other app performance data | Yes | No* | Optional | Analytics | AppMetrica указывает технические сведения, например OS version и screen type. |
| Device or other IDs | Yes | No* | Optional | Analytics; Advertising or marketing (условно для install attribution); App functionality; Developer communications | AppMetrica app/device identifier и install-referrer attribution при analytics opt-in; Firebase installation ID/FCM token и AppMetrica push token при push opt-in. Advertising ID отключён и его модуль исключён. Убрать marketing purpose можно только после подтверждённого отключения attribution/postbacks и проверки production-трафика. |

### Сноска `Shared: No*`

Google не считает `sharing` передачу service provider, который обрабатывает данные от имени разработчика и по его инструкциям. Analytics provider обычно может подпасть под исключение, но это зависит от договора и фактического использования: [Google Play service-provider definition](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en#zippy=%2Cdata-sharing).

Перед выбором `No` необходимо:

- подтвердить, что применимые Firebase terms/DPA и AppMetrica Terms/DPA делают Google и Yandex/Air Smart Advertising Solutions service providers для этих конкретных потоков;
- подтвердить отсутствие независимого profiling, ads use, cross-customer sharing, Data Stream export и передачи другим third parties;
- проверить, что production dashboards не включают BigQuery export, Firebase Analytics, advertising/marketing integrations или новые SDK-модули;
- учесть применимую редакцию [AppMetrica Terms](https://yandex.com/legal/metrica_termsofuse/en/) и [DPA](https://yandex.com/legal/metrica_agreement/en/), а не полагаться только на код.

Если исключение service provider не выполняется хотя бы для одного потока, отметить соответствующий data type как `Shared: Yes` и указать реальные purposes.

## Data types, которые текущий код не собирает

- Location APIs: precise и permission-based approximate location не собираются. Нет location permissions; AppMetrica location tracking выключен и location module исключён. Ответ `Approximate location: No` остаётся неподтверждённым до проверки server-side использования IP, описанной ниже.
- Personal info: name, email, user IDs, address, phone, race/ethnicity, beliefs, sexual orientation, other personal info.
- Financial info: purchase history, payment info, credit score и other financial info. Виртуальные монеты нельзя купить, вывести или обменять и они не имеют реальной стоимости.
- Health and fitness, messages, photos/videos, audio, files/docs, calendar и contacts.
- Web browsing history, in-app search history, installed apps и user-generated content.
- Advertising ID. Manifest удаляет `com.google.android.gms.permission.AD_ID`, AppMetrica advertising tracking выключен, а identifiers module исключён.

Этот список действителен только пока release dependency graph, manifest и production features остаются такими же.

## Не отправлять: обязательные проверки

- [ ] **Production dependency/manifest audit:** распаковать финальный AAB и подтвердить перечисленные SDK/permissions и отсутствие Firebase Analytics, ads, billing, location и AD_ID.
- [ ] **Fresh-install network audit:** до analytics opt-in и push opt-in не должно уходить AppMetrica analytics, FID/FCM token или AppMetrica push registration. Повторить после decline, allow, revoke, notification deny и notification disable.
- [ ] **AppMetrica dashboard:** подтвердить data retention, фактическое отсутствие crash collection, IP masking, exports, postbacks, integrations, audience/marketing функции и отключение advertising identifiers/location. Официальная SDK-таблица описывает defaults, но ответы должны соответствовать этой явно урезанной конфигурации: [AppMetrica Google Play Data safety guidance](https://appmetrica.yandex.com/docs/en/data-security/google-data-safety).
- [ ] **Firebase dashboard:** подтвердить отсутствие Google Analytics и BigQuery delivery metrics export. FCM автоматически использует Firebase Installations; Firebase указывает FID как идентификатор для доставки: [Firebase Android data disclosure](https://firebase.google.com/docs/android/play-data-disclosure) и [Firebase privacy](https://firebase.google.com/support/privacy).
- [ ] **IP handling:** определить, используют ли AppMetrica, Firebase и privacy host/CDN IP для approximate location, identifiers, security или только transport. Google требует классифицировать IP по реальному использованию. Если IP используется для location, добавить `Approximate location`; если как идентификатор, учесть `Device or other IDs`.
- [ ] **Privacy WebView host:** проверить server/CDN access logs, retention, cookies, redirects и subprocessors. Код разрешает только same-origin navigation/subresources, но production HTML, CSP и серверная конфигурация пока неизвестны.
- [ ] **Encryption:** подтвердить TLS для всех analytics, push и privacy requests. Firebase заявляет HTTPS encryption in transit; общий ответ `Yes` возможен только если это верно для каждого фактического потока.
- [ ] **Deletion:** выбрать и внедрить реальный процесс либо оставить ответ `No`. Google допускает request email/form или автоматическое удаление/anonymization в течение 90 дней: [Data deletion guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en#zippy=%2Cdata-deletion).
- [ ] **Sharing/legal role:** зафиксировать письменное решение по service-provider exception для каждого провайдера и региона.
- [ ] **Privacy policy parity:** перечислить те же data types, purposes, providers, countries/regions processing, retention, revoke/deletion process и contact. Регионы обработки должны быть подтверждены по production dashboard, договорам и фактической инфраструктуре; код и in-app disclosure не задают их.
- [ ] **Play Console preview:** после заполнения экспортировать ответы и нормализовать CSV в точную схему `question,answer` с уникальными непустыми вопросами; сохранить screenshots как дополнительное reviewer evidence. Приложить всё к release evidence с `versionCode`. Заполнить `DATA_SAFETY_EVIDENCE_TEMPLATE.json`, указать release commit и SHA-256 network capture, Play Console export, privacy-policy snapshot и внутреннего evidence archive. Network capture должен быть полноценным pcapng с section/interface и минимум двумя непустыми timestamped packet blocks; privacy snapshot должен быть валидным самодостаточным XHTML-документом по контракту из `PLAY_STORE_SUBMISSION.md`. Собрать внешний raw-evidence ZIP с точными путями `manifests/data-safety.json`, `raw/network-capture.pcapng`, `raw/play-console-export.csv`, `raw/privacy-policy-snapshot.html`, `raw/evidence-archive.zip`; внутренний ZIP должен содержать те же capture/export/snapshot. Закрепить внешний ZIP через `V_SLOT_DATA_SAFETY_RAW_EVIDENCE_SHA256`. Только после независимой проверки и подписанного reviewer/device-job evidence задать `V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE` равным этому `versionCode`; следующий commit или `versionCode` требует нового подтверждения.

## Privacy policy: минимальный фактический состав

- Юридическое имя/имя разработчика из listing, V Slot и privacy contact.
- Локальные данные игры и отключённые Android backups.
- AppMetrica opt-in: конкретные gameplay/app-interaction events, diagnostic/performance data и app/device ID, явное отсутствие crash collection и способ отключить аналитику.
- Push opt-in: Firebase installation ID, FCM/AppMetrica push token, доставка и open/delivery events, как отключить уведомления.
- Явное отсутствие ads, purchases, real-money gambling, advertising ID и location collection.
- Получатели/processors, страны/регионы обработки, сроки хранения, удаление/anonymization и права пользователя.
- Privacy-host/CDN logs и retention, если они сохраняются.
- Дата вступления, порядок уведомления об изменениях и version history.

## Источники

- [Google Play: Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- [Google Play: User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [Firebase: Prepare for Google Play data disclosure](https://firebase.google.com/docs/android/play-data-disclosure)
- [Firebase: Privacy and Security](https://firebase.google.com/support/privacy)
- [AppMetrica: Data safety in Google Play](https://appmetrica.yandex.com/docs/en/data-security/google-data-safety)
- [AppMetrica: Advertising IDs](https://appmetrica.yandex.com/docs/en/sdk/android/get-ad-id)
- [AppMetrica Terms](https://yandex.com/legal/metrica_termsofuse/en/)
- [AppMetrica DPA](https://yandex.com/legal/metrica_agreement/en/)
