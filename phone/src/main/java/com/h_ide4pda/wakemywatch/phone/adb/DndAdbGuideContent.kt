package com.h_ide4pda.wakemywatch.phone.adb

object DndAdbGuideContent {
    const val officialPlatformToolsUrl: String = "https://developer.android.com/tools/releases/platform-tools"
    const val adbAndFastbootPlusPlusUrl: String = "https://github.com/K3V1991/ADB-and-FastbootPlusPlus/releases"
    const val htmlFileName: String = "WakeMyWatch ADB Guide.html"

    fun html(): String = """
<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Wake My Watch — настройка DND на часах через ADB</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #07101c;
      --card: #0d1b2d;
      --card2: #101f33;
      --text: #eef4ff;
      --muted: #aab7cc;
      --line: rgba(255,255,255,.10);
      --accent: #7c5cff;
      --accent2: #2f8cff;
      --good: #19d27c;
      --warn: #ffcc66;
      --danger: #ff8b8b;
      --code: #050b14;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
      background:
        radial-gradient(circle at top left, rgba(124,92,255,.22), transparent 34rem),
        radial-gradient(circle at top right, rgba(47,140,255,.18), transparent 30rem),
        var(--bg);
      color: var(--text);
      line-height: 1.55;
    }
    .page {
      width: 100%;
      max-width: 980px;
      margin: 0 auto;
      padding: 28px 18px 60px;
      overflow-x: hidden;
    }
    header {
      padding: 26px 24px;
      border: 1px solid var(--line);
      border-radius: 28px;
      background: linear-gradient(135deg, rgba(13,27,45,.94), rgba(16,31,51,.86));
      box-shadow: 0 18px 50px rgba(0,0,0,.25);
    }
    .brand {
      margin: 0 0 8px;
      text-align: center;
      font-size: clamp(28px, 4.2vw, 48px);
      line-height: 1.02;
      letter-spacing: -0.04em;
      font-weight: 800;
    }
    h1 {
      margin: 0 0 8px;
      font-size: clamp(28px, 4.6vw, 50px);
      letter-spacing: -0.035em;
      line-height: 1.03;
    }
    .subtitle {
      margin: 0;
      color: var(--muted);
      font-size: 18px;
    }
    .badge-row {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin-top: 18px;
    }
    .badge {
      padding: 8px 12px;
      border: 1px solid var(--line);
      border-radius: 999px;
      background: rgba(255,255,255,.05);
      color: var(--muted);
      font-size: 14px;
    }
    section {
      margin-top: 18px;
      padding: 22px;
      border: 1px solid var(--line);
      border-radius: 24px;
      background: rgba(13,27,45,.88);
      max-width: 100%;
      min-width: 0;
      overflow: hidden;
    }
    h2 {
      margin: 0 0 12px;
      font-size: 26px;
      letter-spacing: -0.02em;
    }
    h3 {
      margin: 18px 0 8px;
      font-size: 19px;
    }
    p { margin: 8px 0; color: var(--muted); }
    ul, ol { margin: 8px 0 0 22px; padding: 0; }
    li { margin: 6px 0; min-width: 0; }
    a { color: #8fb7ff; text-decoration: none; overflow-wrap: anywhere; word-break: break-word; }
    a:hover { text-decoration: underline; }
    .note, .warning, .success {
      margin: 14px 0;
      padding: 14px 16px;
      border-radius: 18px;
      border: 1px solid var(--line);
      background: rgba(255,255,255,.045);
    }
    .note strong { color: var(--text); }
    .warning {
      border-color: rgba(255,204,102,.35);
      background: rgba(255,204,102,.08);
    }
    .warning strong { color: var(--warn); }
    .success {
      border-color: rgba(25,210,124,.35);
      background: rgba(25,210,124,.08);
    }
    .success strong { color: var(--good); }
    .path {
      display: inline;
      padding: 2px 7px;
      border-radius: 9px;
      background: rgba(255,255,255,.08);
      color: var(--text);
      font-weight: 650;
      line-height: 1.9;
      box-decoration-break: clone;
      -webkit-box-decoration-break: clone;
    }
    .path-block {
      margin: 8px 0 2px;
      max-width: 100%;
      border-radius: 12px;
      background: rgba(255,255,255,.08);
      overflow-x: auto;
    }
    .path-block code {
      display: block;
      padding: 9px 10px;
      white-space: pre;
      font-weight: 650;
    }
    .code-wrap {
      margin: 12px 0 16px;
      border: 1px solid var(--line);
      border-radius: 18px;
      background: var(--code);
      overflow: hidden;
      max-width: 100%;
      min-width: 0;
    }
    .code-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 12px;
      background: rgba(255,255,255,.055);
      color: var(--muted);
      font-size: 13px;
    }
    pre {
      margin: 0;
      padding: 15px 16px 18px;
      max-width: 100%;
      overflow-x: auto;
      white-space: pre;
    }
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
      color: #eaf2ff;
      font-size: 14px;
    }
    button.copy {
      border: 1px solid rgba(255,255,255,.16);
      background: rgba(124,92,255,.22);
      color: var(--text);
      padding: 7px 10px;
      border-radius: 12px;
      cursor: pointer;
      font-weight: 650;
    }
    button.copy:hover { background: rgba(124,92,255,.34); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 14px;
    }
    .mini-card {
      padding: 16px;
      border: 1px solid var(--line);
      border-radius: 18px;
      background: rgba(255,255,255,.04);
    }
    .mini-card h3 { margin-top: 0; }
    details {
      margin-top: 16px;
      border: 1px solid var(--line);
      border-radius: 18px;
      background: rgba(255,255,255,.035);
      overflow: hidden;
    }
    summary {
      padding: 14px 16px;
      cursor: pointer;
      color: var(--text);
      font-weight: 750;
      user-select: none;
    }
    details[open] summary {
      border-bottom: 1px solid var(--line);
      background: rgba(255,255,255,.035);
    }
    .details-body { padding: 2px 16px 16px; }
    .footer {
      margin-top: 24px;
      color: var(--muted);
      font-size: 13px;
      text-align: center;
    }
    @media (max-width: 520px) {
      .page { padding: 18px 12px 44px; }
      header { padding: 22px 18px; border-radius: 24px; }
      section { padding: 18px 16px; border-radius: 22px; }
      h2 { font-size: 24px; }
      pre { padding: 13px 12px 15px; }
    }
    @media print {
      body { background: #fff; color: #111; }
      header, section { background: #fff; border-color: #ddd; box-shadow: none; }
      .subtitle, p, .badge, .code-head, .footer { color: #444; }
      a { color: #0645ad; text-decoration: underline; }
      .code-wrap { border-color: #ddd; background: #f7f7f7; }
      code { color: #111; }
      button.copy { display: none; }
    }
  </style>
</head>
<body>
  <div class="page">
    <header>
      <div class="brand">Wake My Watch</div>
      <h1>Настройка DND на часах через ADB</h1>
      <p class="subtitle">Пошаговая инструкция для выдачи разрешений Wake My Watch на OnePlus Watch.</p>
      <div class="badge-row">
        <span class="badge">Для пользователя</span>
        <span class="badge">Windows</span>
        <span class="badge">OnePlus Watch</span>
        <span class="badge">DND Sync</span>
      </div>
    </header>

    <section>
      <h2>1. Для настройки потребуется</h2>
      <ul>
        <li>телефон с установленным Wake My Watch;</li>
        <li>часы OnePlus Watch с установленным Wake My Watch;</li>
        <li>компьютер с Windows;</li>
        <li>оригинальная зарядная база и USB-кабель для подключения часов к компьютеру;</li>
        <li>для беспроводного варианта — одна и та же Wi‑Fi сеть для часов и компьютера;</li>
        <li>ADB (Android Platform Tools).</li>
      </ul>
    </section>

    <section>
      <h2>2. Скачайте ADB</h2>
      <div class="grid">
        <div class="mini-card">
          <h3>Официальный вариант</h3>
          <p>Скачать ADB (Android Platform Tools):</p>
          <p><a href="https://developer.android.com/tools/releases/platform-tools" target="_blank" rel="noopener">https://developer.android.com/tools/releases/platform-tools</a></p>
        </div>
        <div class="mini-card">
          <h3>Облегчённый вариант</h3>
          <p>Если у вас нет ADB, можно скачать облегчённый вариант со страницы релизов ADB and Fastboot++:</p>
          <p><a href="https://github.com/K3V1991/ADB-and-FastbootPlusPlus/releases" target="_blank" rel="noopener">https://github.com/K3V1991/ADB-and-FastbootPlusPlus/releases</a></p>
        </div>
      </div>
    </section>

    <section>
      <h2>3. Где искать ADB и как открыть командную строку</h2>
      <p>После скачивания ADB распакуйте архив в удобное место, например:</p>
      <p><span class="path">C:\platform-tools</span></p>
      <p>Внутри этой папки должен быть файл:</p>
      <p><span class="path">adb.exe</span></p>

      <div class="note">
        <strong>Важно:</strong> команды нужно выполнять именно из папки, где лежит <span class="path">adb.exe</span>, если ADB не добавлен в PATH.
      </div>

      <h3>Если вы скачали ADB and Fastboot++</h3>
      <ol>
        <li>Распакуйте папку из скачанного архива ADB and Fastboot++.</li>
        <li>Откройте распакованную папку.</li>
        <li>Запустите файл <span class="path">Open CMD.bat</span>.</li>
      </ol>
      <p>Откроется командная строка уже в нужной папке, где доступна команда <span class="path">adb</span>.</p>

      <h3>Если вы скачали Android Platform Tools от Google</h3>
      <ol>
        <li>Распакуйте архив <span class="path">platform-tools-latest-windows.zip</span>.</li>
        <li>Откройте папку <span class="path">platform-tools</span>.</li>
        <li>Кликните по пустому месту внутри папки правой кнопкой мыши с зажатой клавишей <strong>Shift</strong>.</li>
        <li>Выберите <strong>“Открыть окно PowerShell здесь”</strong>.</li>
      </ol>

      <h3>Если такого пункта нет</h3>
      <ol>
        <li>Нажмите на адресную строку в Проводнике.</li>
        <li>Введите <span class="path">cmd</span>.</li>
        <li>Нажмите Enter.</li>
      </ol>
      <p>Откроется командная строка сразу в нужной папке.</p>

      <h3>Проверьте, что ADB работает</h3>
      <p>Введите:</p>
      <div class="code-wrap">
        <div class="code-head"><span>Проверка ADB</span><button class="copy" data-copy="adb version">Копировать</button></div>
        <pre><code>adb version</code></pre>
      </div>
      <p>Если ADB установлен правильно, появится версия Android Debug Bridge.</p>

      <div class="warning">
        <strong>Если появляется ошибка</strong> “adb не является внутренней или внешней командой”, значит Windows не нашла файл <span class="path">adb.exe</span>. Чаще всего это происходит по одной из двух причин: командная строка открыта не в папке <span class="path">platform-tools</span>, или ADB не добавлен в системный PATH.
        <br><br>
        <strong>Что сделать:</strong>
        <ol>
          <li>Закройте текущее окно PowerShell/командной строки.</li>
          <li>Откройте в Проводнике папку, где лежит <span class="path">adb.exe</span>.</li>
          <li>Кликните по пустому месту внутри папки правой кнопкой мыши с зажатой клавишей <strong>Shift</strong>.</li>
          <li>Выберите <strong>“Открыть окно PowerShell здесь”</strong>.</li>
          <li>Снова выполните команду <span class="path">adb version</span>.</li>
        </ol>
        Если после этого ошибка остаётся, проверьте, что файл действительно называется <span class="path">adb.exe</span> и находится в открытой папке.
      </div>

      <h3>Дополнительно: как добавить ADB в PATH</h3>
      <p>Это не обязательно, но удобно, если вы хотите запускать <span class="path">adb</span> из любой папки.</p>
      <ol>
        <li>Нажмите <span class="path">Win + R</span>, введите <span class="path">sysdm.cpl</span> и нажмите Enter.</li>
        <li>Перейдите на вкладку <strong>«Дополнительно»</strong> и нажмите кнопку <strong>«Переменные среды»</strong>.</li>
        <li>В разделе <strong>«Системные переменные»</strong> найдите переменную <span class="path">Path</span>, выделите её и нажмите <strong>«Изменить»</strong>.</li>
        <li>Нажмите <strong>«Создать»</strong> и вставьте путь к папке с <span class="path">adb</span>, например:
          <div class="path-block"><code>C:\Users\Имя_пользователя\AppData\Local\Android\Sdk\platform-tools</code></div>
        </li>
        <li>Нажмите <strong>OK</strong> во всех окнах и перезапустите PowerShell/командную строку.</li>
      </ol>
    </section>

    <section>
      <h2>4. Подготовьте телефон</h2>
      <p>На телефоне откройте Wake My Watch → Разрешения и фон.</p>
      <p>Проверьте, что выданы основные разрешения, которые нужны приложению для работы:</p>
      <ul>
        <li>доступ к уведомлениям;</li>
        <li>разрешение показывать уведомления;</li>
        <li>доступ к режиму «Не беспокоить»;</li>
        <li>работа в фоне / батарея;</li>
        <li>точные напоминания.</li>
      </ul>
    </section>

    <section>
      <h2>5. Подготовьте часы</h2>
      <ol>
        <li>
          <p>Откройте на часах:</p>
          <p><span class="path">Настройки → Другое → О часах → Прочая информация о версии</span></p>
        </li>
        <li>
          <p>Нажмите многократно по номеру сборки, пока не появится сообщение о включении режима разработчика.</p>
        </li>
        <li>
          <p>Затем откройте:</p>
          <p><span class="path">Настройки → Другое → Для разработчиков</span></p>
        </li>
        <li>
          <p>Включите переключатель:</p>
          <ul>
            <li><strong>Отладка через ADB</strong></li>
          </ul>
        </li>
      </ol>
      <div class="note">
        <strong>Основной и самый простой способ:</strong> подключить часы к компьютеру на оригинальной зарядной базе. Беспроводная отладка для этого не требуется.
      </div>
    </section>

    <section>
      <h2>6. Подключите часы к компьютеру</h2>
      <h3>Рекомендуемый способ: через оригинальную зарядную базу</h3>
      <ol>
        <li>Убедитесь, что на часах включена <strong>«Отладка через ADB»</strong>.</li>
        <li>Установите часы на оригинальную зарядную базу.</li>
        <li>Подключите базу к компьютеру с помощью USB-кабеля.</li>
        <li>Если на часах появится запрос на разрешение отладки, подтвердите его.</li>
      </ol>
      <p>Проверьте подключение:</p>
      <div class="code-wrap">
        <div class="code-head"><span>Список устройств</span><button class="copy" data-copy="adb devices">Копировать</button></div>
        <pre><code>adb devices</code></pre>
      </div>
      <p>В списке должно появиться устройство часов со статусом <span class="path">device</span>.</p>
      <div class="success">
        <strong>Готово:</strong> если часы видны в <span class="path">adb devices</span>, можно переходить к пункту 7. Дополнительное сопряжение и ввод IP-адреса не нужны.
      </div>

      <details>
        <summary>Беспроводная отладка — альтернативный способ</summary>
        <div class="details-body">
          <p>Используйте этот вариант только если подключить часы к компьютеру через оригинальную зарядную базу невозможно.</p>
          <ol>
            <li>На часах откройте <span class="path">Настройки → Другое → Для разработчиков</span>.</li>
            <li>Убедитесь, что включена <strong>«Отладка через ADB»</strong>.</li>
            <li>Дополнительно включите <strong>«Отладка по Wi‑Fi»</strong>.</li>
            <li>Нажмите <span class="path">Подключить новое устройство</span>.</li>
          </ol>

          <p>На экране часов появятся IP-адрес, порт и код сопряжения. Они могут выглядеть примерно так:</p>
          <ul>
            <li><span class="path">192.168.0.100:5555</span></li>
            <li><span class="path">123456</span></li>
          </ul>

          <h3>Сопряжение</h3>
          <p>В PowerShell/командной строке на компьютере выполните:</p>
          <div class="code-wrap">
            <div class="code-head"><span>Команда сопряжения</span><button class="copy" data-copy="adb pair IP_ЧАСОВ:PAIR_PORT PAIR_CODE">Копировать</button></div>
            <pre><code>adb pair IP_ЧАСОВ:PAIR_PORT PAIR_CODE</code></pre>
          </div>

          <p>Пример:</p>
          <div class="code-wrap">
            <div class="code-head"><span>Пример</span><button class="copy" data-copy="adb pair 192.168.0.100:5555 123456">Копировать</button></div>
            <pre><code>adb pair 192.168.0.100:5555 123456</code></pre>
          </div>

          <h3>Подключение</h3>
          <p>После успешного сопряжения вернитесь на экран отладки по Wi‑Fi на часах. Там будет адрес подключения часов. Выполните:</p>
          <div class="code-wrap">
            <div class="code-head"><span>Команда подключения</span><button class="copy" data-copy="adb connect IP_ЧАСОВ:CONNECT_PORT">Копировать</button></div>
            <pre><code>adb connect IP_ЧАСОВ:CONNECT_PORT</code></pre>
          </div>

          <p>Пример:</p>
          <div class="code-wrap">
            <div class="code-head"><span>Пример</span><button class="copy" data-copy="adb connect 192.168.0.100:4444">Копировать</button></div>
            <pre><code>adb connect 192.168.0.100:4444</code></pre>
          </div>

          <h3>Проверка подключения</h3>
          <div class="code-wrap">
            <div class="code-head"><span>Список устройств</span><button class="copy" data-copy="adb devices">Копировать</button></div>
            <pre><code>adb devices</code></pre>
          </div>
          <p>В списке должно появиться устройство с адресом часов и статусом <span class="path">device</span>.</p>
        </div>
      </details>
    </section>

    <section>
      <h2>7. Выдайте доступ к уведомлениям на часах</h2>
      <p>Это разрешение нужно, чтобы Wake My Watch мог отслеживать DND-состояние на часах.</p>
      <div class="code-wrap">
        <div class="code-head"><span>Команда</span><button class="copy" data-copy="adb shell cmd notification allow_listener com.h_ide4pda.wakemywatch/com.h_ide4pda.wakemywatch.watch.dnd.WatchDndSyncNotificationService">Копировать</button></div>
        <pre><code>adb shell cmd notification allow_listener com.h_ide4pda.wakemywatch/com.h_ide4pda.wakemywatch.watch.dnd.WatchDndSyncNotificationService</code></pre>
      </div>

      <h3>Если подключено несколько устройств</h3>
      <p>Укажите идентификатор часов из <span class="path">adb devices</span>. При USB-подключении это будет serial, при беспроводном — IP и порт.</p>
      <div class="code-wrap">
        <div class="code-head"><span>Команда с -s</span><button class="copy" data-copy="adb -s SERIAL_ЧАСОВ_ИЛИ_IP:PORT shell cmd notification allow_listener com.h_ide4pda.wakemywatch/com.h_ide4pda.wakemywatch.watch.dnd.WatchDndSyncNotificationService">Копировать</button></div>
        <pre><code>adb -s SERIAL_ЧАСОВ_ИЛИ_IP:PORT shell cmd notification allow_listener com.h_ide4pda.wakemywatch/com.h_ide4pda.wakemywatch.watch.dnd.WatchDndSyncNotificationService</code></pre>
      </div>
    </section>

    <section>
      <h2>8. Выдайте доступ к режиму «Не беспокоить» на часах</h2>
      <p>Это разрешение нужно, чтобы Wake My Watch мог переносить режим «Не беспокоить» с телефона на часы. На телефонах OnePlus это и без ADB делает OHealth — доступ актуален на остальных телефонах. Он же задействуется при синхронизации профиля звука: режим «Без звука» на телефоне включает «Не беспокоить» на часах.</p>
      <div class="code-wrap">
        <div class="code-head"><span>Команда</span><button class="copy" data-copy="adb shell cmd notification allow_dnd com.h_ide4pda.wakemywatch">Копировать</button></div>
        <pre><code>adb shell cmd notification allow_dnd com.h_ide4pda.wakemywatch</code></pre>
      </div>

      <h3>Если подключено несколько устройств</h3>
      <p>Укажите идентификатор часов из <span class="path">adb devices</span>. При USB-подключении это будет serial, при беспроводном — IP и порт.</p>
      <div class="code-wrap">
        <div class="code-head"><span>Команда с -s</span><button class="copy" data-copy="adb -s SERIAL_ЧАСОВ_ИЛИ_IP:PORT shell cmd notification allow_dnd com.h_ide4pda.wakemywatch">Копировать</button></div>
        <pre><code>adb -s SERIAL_ЧАСОВ_ИЛИ_IP:PORT shell cmd notification allow_dnd com.h_ide4pda.wakemywatch</code></pre>
      </div>
    </section>

    <section>
      <h2>9. Проверьте результат</h2>
      <p>Откройте Wake My Watch на часах и нажмите <strong>“Обновить статус”</strong>.</p>
      <p>В разделе DND должно быть:</p>
      <div class="success">
        <strong>DND Sync готов</strong><br>
        Доступ к уведомлениям на часах — предоставлен.<br>
        Доступ к режиму «Не беспокоить» на часах — предоставлен.
      </div>
      <p>На телефоне можно нажать <strong>“Проверить статус часов”</strong>.</p>
    </section>

    <section>
      <h2>10. После настройки отключите отладку</h2>
      <div class="warning">
        <strong>Важно:</strong> после завершения настройки отключите <strong>«Отладку через ADB»</strong> и <strong>«Отладку по Wi‑Fi»</strong> (если она была включена). Это повысит безопасность устройства и поможет избежать лишнего расхода заряда аккумулятора.
      </div>
      <p>Путь:</p>
      <p><span class="path">Настройки → Другое → Для разработчиков</span></p>
    </section>

    <section>
      <h2>Если что-то не получилось</h2>
      <h3>ADB не найден</h3>
      <p>Откройте PowerShell/командную строку из папки, где лежит <span class="path">adb.exe</span>, и снова выполните <span class="path">adb version</span>.</p>

      <h3>Часы не подключаются по USB</h3>
      <ul>
        <li>убедитесь, что на часах включена <strong>«Отладка через ADB»</strong>;</li>
        <li>используйте оригинальную зарядную базу и исправный USB-кабель;</li>
        <li>подключите базу напрямую к другому USB-порту компьютера;</li>
        <li>проверьте экран часов и подтвердите запрос на разрешение отладки, если он появился;</li>
        <li>снова выполните <span class="path">adb devices</span>.</li>
      </ul>

      <h3>Часы не подключаются по Wi‑Fi</h3>
      <ul>
        <li>убедитесь, что на часах включены <strong>«Отладка через ADB»</strong> и <strong>«Отладка по Wi‑Fi»</strong>;</li>
        <li>убедитесь, что компьютер и часы находятся в одной Wi‑Fi сети;</li>
        <li>попробуйте снова выполнить <span class="path">adb connect IP_ЧАСОВ:CONNECT_PORT</span>;</li>
        <li>если IP или порт изменились, используйте новые значения с экрана часов.</li>
      </ul>

      <h3>Команда выполнилась, но статус не изменился</h3>
      <p>Откройте Wake My Watch на часах и нажмите <strong>“Обновить статус”</strong>. Иногда статус обновляется только после повторной проверки.</p>
    </section>

    <p class="footer">
  ADB guide preview for Wake My Watch by H_IDE<br>
  © H_IDE. Wake My Watch распространяется бесплатно и предоставляется «как есть».
  Команды ADB и все прочие действия, связанные с программой, выполняются пользователем вручную и на свой риск.
</p>
  </div>

  <script>
    document.querySelectorAll('button.copy').forEach((button) => {
      button.addEventListener('click', async () => {
        const text = button.getAttribute('data-copy') || '';
        try {
          await navigator.clipboard.writeText(text);
          const old = button.textContent;
          button.textContent = 'Скопировано';
          setTimeout(() => button.textContent = old, 1200);
        } catch (e) {
          alert('Не удалось скопировать. Выделите команду вручную.');
        }
      });
    });
  </script>
</body>
</html>

    """.trimIndent()
}
