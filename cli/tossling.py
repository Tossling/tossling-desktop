import base64
import getpass
import json
import os
import plistlib
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
import legacy
from helper import Helper
from tosslinglib import BAD, BUNDLED_APP, CACHE, CONFIG, CONFIG_DIR, HOME, OK, ROOT, WARN, has_flag, paint, t, tilde, version

args = sys.argv[1:]
INSTALLED_CLI = "/Applications/Tossling.app/Contents/Resources/tossling/bin/tossling"
if not BUNDLED_APP and not (os.environ.get("TOSSLING_FROM_SOURCE") or os.environ.get("TOSSY_FROM_SOURCE")) and os.access(INSTALLED_CLI, os.X_OK):
    os.execv(INSTALLED_CLI, [INSTALLED_CLI, *args])
ACTIONS = ("status", "setup", "pair", "invite", "join", "rename", "on", "off", "pause", "resume", "send", "images", "auto",
           "hotkey", "log", "remove", "config", "language", "project")
if args[:1] in (["-V"], ["--version"]):
    print(version())
    sys.exit(0)
if has_flag(args, "-h", "--help") or (args and args[0] not in ACTIONS and not (args[0] == "adopt" and BUNDLED_APP)):
    print(t("tossling                    меню с действиями (без терминала — то же, что status)", "tossling                    a menu of actions (without a terminal: the same as status)"))
    print(t("tossling status             состояние: работает ли, связь с сервером, устройства, что передано", "tossling status             is it running, the server connection, devices, what was sent"))
    print(t("tossling setup [<сервер>]   подключить свой сервер и телефон с приложением Tossling (токен: TOSSLING_TOKEN или ввод)", "tossling setup [<server>]   connect your server and a phone with the Tossling app (token: TOSSLING_TOKEN or typed)"))
    print(t("tossling pair [--new]       показать QR для телефона; --new — новый ключ, все устройства подключать заново", "tossling pair [--new]       QR code for a phone; --new: a new key, every device has to pair again"))
    print(t("tossling invite             код для второго Mac (10 минут, один раз)", "tossling invite             a code for another Mac (10 minutes, once)"))
    print(t("tossling join <код>         подключить этот Mac к комнате другого: tossling join ntfy.example.com/ABCD-EFGH", "tossling join <code>        join the room of another Mac: tossling join tossling.example.com/ABCD-EFGH"))
    print(t("tossling rename             переименовать устройство в комнате (выбор из списка)", "tossling rename             rename a device of the room (pick from a list)"))
    print(t("tossling rename <имя>       новое имя этого Mac — его увидят все в комнате", "tossling rename <name>      a new name for this Mac, seen by everyone in the room"))
    print(t("tossling rename <кто> <имя> как называть другое устройство на этом Mac (пустое имя — вернуть его собственное)", "tossling rename <who> <name> what to call another device on this Mac (empty: its own name again)"))
    print(t("tossling send <файл|текст>  отправить текст, картинку или любой файл до 500 МБ на все устройства", "tossling send <file|text>   send text, an image or any file up to 500 MB to every device"))
    print(t("  … | tossling send         отправить то, что пришло в пайп (текст или файл)", "  … | tossling send         send what comes through the pipe (text or a file)"))
    print(t("  --to <кто>             только этому устройству (имя из status; можно несколько)", "  --to <who>             only to this device (a name from status; may repeat)"))
    print(t("  --pick                 спросить в окне, кому отправить", "  --pick                 ask in a window whom to send to"))
    print(t("tossling pause [мин]        не отправлять буфер этого Mac (по умолчанию 30 мин)", "tossling pause [min]        do not send this Mac's clipboard (30 min by default)"))
    print(t("tossling resume             снова отправлять", "tossling resume             send again"))
    print(t("tossling images on|off      передавать ли картинки", "tossling images on|off      whether to send images"))
    print(t("tossling auto on|off        отправлять ли буфер сам по ⌘C (off — только по ⌃⌥⌘C)", "tossling auto on|off        send the clipboard by itself on ⌘C (off: only on the hotkey)"))
    print(t("tossling hotkey on|off      ⌃⌥⌘C отправляет то, что в буфере, включая файлы из Finder", "tossling hotkey on|off      the hotkey copies the selection and sends it, Finder files too"))
    print(t("tossling log                что передано и почему что-то пропущено", "tossling log                what was sent and why something was skipped"))
    print(t("tossling on | off           включить или выключить помощника", "tossling on | off           start or stop the helper"))
    print(t("tossling remove             выключить и удалить помощника и настройки", "tossling remove             stop and remove the helper and its settings"))
    print(t("tossling config --json      сервер, токен и каналы проектов для других программ (в выводе токен)", "tossling config --json      server, token and project channels for other programs (prints the token)"))
    print(t("tossling language ru|en|auto  язык меню и сообщений (auto — как в системе)", "tossling language ru|en|auto  the language of menus and messages (auto: as the system)"))
    print(t("tossling project [list]     проекты сервера: каналы, куда сервисы шлют события", "tossling project [list]     projects of the server: channels services publish events to"))
    print(t("tossling project add <канал> [название] [--publisher <имя>]  новый проект и токен отправителя (Tossling Server)", "tossling project add <channel> [name] [--publisher <name>]  a new project and its publisher token (Tossling Server)"))
    print(t("tossling project remove <канал>  удалить проект: его токен перестанет работать", "tossling project remove <channel>  delete a project: its token stops working"))
    print()
    print(t("Общий буфер обмена твоих Mac и Android-телефона через свой сервер: скопировал на одном —", "One clipboard for your Macs and Android phone through your own server: copy on one,"))
    print(t("вставляешь на остальных. Всё шифруется на устройствах, пароли из менеджеров паролей не передаются.", "paste on the others. Everything is encrypted on the devices; passwords from password managers stay put."))
    print(t("Файлы уходят только явно: горячая клавиша или в Finder правый клик → «Отправить в Tossling».", "Files go only when you ask: the hotkey, or right click in Finder → «Send via Tossling»."))
    sys.exit(0 if has_flag(args, "-h", "--help") else 2)
cmd = args[0] if args else "status"

QR = os.path.join(CACHE, "pair.png")
TOKEN_RE = re.compile(r"^tk_[A-Za-z0-9]{20,}$")
agent = Helper()


def read_conf():
    try:
        with open(CONFIG) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_conf(conf):
    os.makedirs(CONFIG_DIR, mode=0o700, exist_ok=True)
    tmp = CONFIG + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(conf, f)
    os.replace(tmp, CONFIG)


def write_conf(**values):
    conf = read_conf()
    conf.update(values)
    save_conf(conf)


def drop_conf(key):
    conf = read_conf()
    conf.pop(key, None)
    save_conf(conf)


def not_configured():
    c = read_conf()
    if c.get("server") and c.get("token"):
        return (t("Этот Mac не в комнате: его отключили с другого устройства или настройки сброшены.\n" "Вернуться: tossling setup --new — новая комната и QR для телефона,\n" "или tossling join <код> — в комнату другого Mac (код даёт tossling invite на нём).", "This Mac is not in a room: another device disconnected it or the settings were reset.\n" "Come back with tossling setup --new (a new room and a QR code for the phone)\n" "or tossling join <code> (the room of another Mac; tossling invite there gives the code)."))
    return t("Сначала tossling setup <сервер>", "Run tossling setup <server> first")


def configured():
    c = read_conf()
    return all(c.get(k) for k in ("server", "token", "key")) and bool(c.get("room") or c.get("to_mac"))


def migrate():
    c = read_conf()
    if c.get("room") or not c.get("to_mac"):
        if c.get("room") and not c.get("device_id"):
            write_conf(device_id=secrets.token_hex(8))
        return False
    conf = {k: v for k, v in c.items() if k not in ("to_mac", "to_phone")}
    conf.update(room=f"{channel_prefix(c.get('server', ''))}{secrets.token_hex(12)}", legacy_to_mac=c["to_mac"], legacy_to_phone=c["to_phone"],
                device_id=c.get("device_id") or secrets.token_hex(8))
    save_conf(conf)
    return True


def http(method, url, token, body=None, headers=None, timeout=15):
    req = urllib.request.Request(url, data=body, method=method,
                                 headers={"Authorization": f"Bearer {token}", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except (urllib.error.URLError, OSError) as e:
        return 0, str(getattr(e, "reason", e)).encode()


def error_text(raw):
    try:
        return json.loads(raw).get("error") or raw.decode(errors="replace")
    except ValueError:
        return raw.decode(errors="replace").strip()


def read_token():
    env = (os.environ.get("TOSSLING_TOKEN") or os.environ.get("TOSSY_TOKEN", "")).strip()
    if TOKEN_RE.match(env):
        return env
    if sys.stdin.isatty():
        return getpass.getpass(t("Токен (tk_…, при вводе не виден): ", "Token (tk_…, hidden while typing): ")).strip()
    clip = subprocess.run(["pbpaste"], capture_output=True, text=True).stdout.strip()
    if TOKEN_RE.match(clip):
        subprocess.run(["pbcopy"], input="", text=True)
        print(t("Взял токен из буфера обмена и очистил буфер.", "Took the token from the clipboard and cleared the clipboard."))
        return clip
    return ""


def normalize_server(value):
    value = value.strip().rstrip("/")
    if value and not re.match(r"^https?://", value):
        value = "https://" + value
    return value


def channel_prefix(server):
    code, raw = http("GET", f"{server.rstrip('/')}/v1/tossling/health", "", timeout=10) if server else (0, b"")
    try:
        return "tossling-" if code == 200 and json.loads(raw).get("server") == "tossling-server" else "tossy-"
    except ValueError:
        return "tossy-"


def api(method, base, path, token, body=None, headers=None):
    code, raw = http(method, f"{base}/v1/tossling/{path}", token, body, headers)
    if code == 404 and not raw.strip().startswith(b"{\"error\""):
        code, raw = http(method, f"{base}/v1/tossy/{path}", token, body, headers)
    return code, raw


def new_channel():
    conf = read_conf()
    device_id = conf.get("device_id") or secrets.token_hex(8)
    return {"room": f"{channel_prefix(conf.get('server', ''))}{secrets.token_hex(12)}", "key": base64.b64encode(secrets.token_bytes(32)).decode(),
            "device_id": device_id, "owner": device_id, "legacy_to_mac": "", "legacy_to_phone": ""}


CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def new_code():
    raw = "".join(secrets.choice(CODE_ALPHABET) for _ in range(8))
    return f"{raw[:4]}-{raw[4:]}"


def setup():
    conf = read_conf()
    positional = [a for a in args[1:] if not a.startswith("--")]
    server = normalize_server(positional[0] if positional else "")
    if not server:
        hint = f" [{conf['server']}]" if conf.get("server") else ""
        server = normalize_server(input(t(f"Адрес сервера, например https://tossling.example.com{hint}: ", f"Server address, for example https://tossling.example.com{hint}: ")) or conf.get("server", ""))
    if not server:
        sys.exit(t("Нужен адрес сервера.", "The server address is needed."))
    token = conf.get("token", "") if conf.get("server") == server and "--token" not in args else ""
    if token:
        print(t(f"Токен: прежний из {tilde(CONFIG)} (новый — tossling setup --token).", f"Token: the previous one from {tilde(CONFIG)} (a new one: tossling setup --token)."))
    else:
        print(t(f"Токен — на странице настройки сервера. На этом Mac он хранится только в {tilde(CONFIG)} (права 600).", f"The token is on the setup page of the server. On this Mac it is kept only in {tilde(CONFIG)} (mode 600)."))
        token = read_token()
    if not token:
        sys.exit(t("Нет токена. Скопируй его (tk_…) и запусти команду ещё раз.", "No token. Copy it (tk_…) and run the command again."))
    code, raw = http("GET", f"{server}/v1/account", token)
    if code != 200:
        sys.exit(t(f"{BAD} сервер не принял токен: HTTP {code} {error_text(raw)}", f"{BAD} the server refused the token: HTTP {code} {error_text(raw)}"))
    user = json.loads(raw).get("username", "?")
    print(t(f"{OK} {server}, пользователь {user}", f"{OK} {server}, user {user}"))
    probe = f"{channel_prefix(server)}probe-{secrets.token_hex(6)}"
    code, raw = http("PUT", f"{server}/{probe}", token, body=b"probe", headers={"X-Filename": "probe.bin"})
    if code != 200:
        print(t(f"{WARN} не могу публиковать: HTTP {code} {error_text(raw)} — проверь права пользователя на сервере", f"{WARN} cannot publish: HTTP {code} {error_text(raw)}; check the user's access on the server"))
    elif "attachment" not in json.loads(raw):
        print(t(f"{WARN} на сервере выключены вложения: картинки и длинные тексты не пройдут " f"(вложения на сервере)", f"{WARN} attachments are off on the server: images and long texts will not get through " f"(server attachments)"))
    else:
        print(t(f"{OK} вложения работают, картинки пройдут", f"{OK} attachments work, images will get through"))
    values = {"server": server, "token": token}
    if not configured() or "--new" in args:
        write_conf(server=server)
        values.update(new_channel())
    values.setdefault("images", conf.get("images", True))
    write_conf(**values)
    start()
    pair(fresh=False)


def start():
    if migrate():
        print(t(f"{OK} настройки переведены на комнату: телефон переедет сам, как только получит сообщение.", f"{OK} the settings moved to a room: the phone follows as soon as it gets a message."))
    write_conf(paused_until=0)
    if not agent.bundled and agent.source_hash() != (open(agent.stamp).read().strip() if os.path.exists(agent.stamp) else ""):
        agent.stop()
    agent.build()
    if agent.installed() and agent.state():
        agent.restart()
        if agent.wait_state():
            return
    started = agent.start()
    if started == "requiresApproval":
        if not agent.approve_login_item():
            sys.exit(1)
        started = agent.start()
    if not started:
        sys.exit(t(f"Помощник не запустился. Лог: {tilde(agent.log)}", f"The helper did not start. Log: {tilde(agent.log)}"))
    install_quick_actions()


def paired_at():
    s = agent.state() or {}
    return s.get("phone_at") or 0, s.get("phone", "")


def pair(fresh=None):
    if not configured():
        sys.exit(not_configured())
    if fresh is None and "--new" in args:
        write_conf(**new_channel())
        start()
        print(t(f"{OK} новый ключ и каналы; телефон, подключённый раньше, больше ничего не получит.", f"{OK} a new key and channels; a phone paired before gets nothing any more."))
    agent.build()
    os.makedirs(os.path.dirname(QR), exist_ok=True)
    r = subprocess.run([agent.bin, "--config", CONFIG, "--qr", QR], capture_output=True, text=True, timeout=30)
    if r.returncode != 0 or not os.path.exists(QR):
        sys.exit(t(f"Не сделал QR: {r.stdout.strip() or r.stderr.strip()}", f"Could not make the QR code: {r.stdout.strip() or r.stderr.strip()}"))
    asked = time.time()
    print(t("Открой Tossling на телефоне → «Подключить» и наведи камеру на QR (жду до 3 минут).", "Open Tossling on the phone → «Pair with a Mac» and point the camera at the QR code (waiting up to 3 minutes)."))
    print(paint(t("  В QR — ключ шифрования и токен: не показывай его никому и не делай скриншот.", "  The QR code holds the encryption key and the token: do not show it to anyone or take a screenshot."), "2"))
    viewer = subprocess.Popen(["qlmanage", "-p", QR], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(180):
            at, name = paired_at()
            if at >= asked:
                print(t(f"{OK} подключён {name}", f"{OK} paired {name}"))
                break
            time.sleep(1)
        else:
            print(t("Не дождался ответа телефона. Повторить: tossling pair", "No answer from the phone. Again: tossling pair"))
    except KeyboardInterrupt:
        print()
    finally:
        viewer.terminate()
        try:
            os.remove(QR)
        except OSError:
            pass


def invite():
    if not configured():
        sys.exit(not_configured())
    migrate()
    agent.build()
    code = new_code()
    host = read_conf()["server"].split("://", 1)[-1]
    proc = subprocess.Popen([agent.bin, "--config", CONFIG, "--invite", code], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        first = proc.stdout.readline().strip()
        if first != "ok":
            proc.wait()
            reason = first or proc.stderr.read().strip()
            sys.exit(t(f"Не создал приглашение: {reason}", f"Could not create the invite: {reason}"))
        print(t("На втором Mac выполни:", "On the other Mac run:"))
        print()
        print(f"    tossling join {host}/{code}")
        print()
        print(paint(t("  Приглашение не хранится на сервере: оно уходит, только пока эта команда ждёт, до 10 минут.", "  The invite is not stored on the server: it goes out only while this command waits, up to 10 minutes."), "2"))
        print(paint(t("  Кто знает код, тот войдёт в комнату. Не публикуй его. Ctrl+C — отменить.", "  Whoever knows the code joins the room. Do not post it. Ctrl+C cancels."), "2"), flush=True)
        result = proc.stdout.read().strip()
        proc.wait()
    except KeyboardInterrupt:
        proc.terminate()
        print()
        sys.exit(t("Приглашение отменено.", "The invite is cancelled."))
    if proc.returncode == 0:
        print(t(f"{OK} {result or 'Mac'} в комнате.", f"{OK} {result or 'Mac'} is in the room."))
    elif result == "timeout":
        sys.exit(t("За 10 минут никто не подключился. Новое приглашение: tossling invite", "Nobody joined within 10 minutes. A new invite: tossling invite"))
    else:
        reason = result or proc.stderr.read().strip()
        sys.exit(t(f"{BAD} приглашение прервалось: {reason}", f"{BAD} the invite broke off: {reason}"))


def join():
    if len(args) < 2:
        sys.exit(t("tossling join <сервер>/<КОД> — команду показывает tossling invite на первом Mac", "tossling join <server>/<CODE>: tossling invite on the first Mac shows the command"))
    target = args[1].strip()
    server, _, code = target.rpartition("/")
    server = normalize_server(server or read_conf().get("server", ""))
    if not server or not re.match(r"^[0-9A-Za-z]{4}-?[0-9A-Za-z]{4}$", code):
        sys.exit(t("Нужно вида: tossling join tossling.example.com/ABCD-EFGH", "Expected: tossling join tossling.example.com/ABCD-EFGH"))
    agent.build()
    old = read_conf()
    device_id = old.get("device_id") or secrets.token_hex(8)
    out = os.path.join(CONFIG_DIR, "join.tmp")
    try:
        r = subprocess.run([agent.bin, "--config", "/dev/null", "--join", server, code, "--out", out,
                            "--id", device_id, "--name", old.get("name", "")],
                           capture_output=True, text=True, timeout=120)
        if r.returncode != 0 or not os.path.exists(out):
            sys.exit(t(f"{BAD} {r.stdout.strip() or r.stderr.strip() or 'не получилось'}", f"{BAD} {r.stdout.strip() or r.stderr.strip() or 'did not work'}"))
        with open(out) as f:
            invite = json.load(f)
    finally:
        try:
            os.remove(out)
        except OSError:
            pass
    conf = {"server": normalize_server(invite["s"]), "token": invite["t"], "room": invite["r"], "key": invite["k"],
            "device_id": device_id, "owner": invite.get("o", ""), "images": old.get("images", True)}
    conf.update({k: old[k] for k in ("name", "aliases", "identity", "notify_app") if old.get(k)})
    save_conf(conf)
    start()
    print(t(f"{OK} этот Mac в одной комнате с {r.stdout.strip()}: скопированное здесь появится там и на телефоне.", f"{OK} this Mac is in one room with {r.stdout.strip()}: what you copy here shows up there and on the phone."))


def on():
    if not configured():
        sys.exit(not_configured())
    start()
    print(t(f"{OK} tossling включён: буфер обмена общий с телефоном.", f"{OK} tossling is on: one clipboard with the phone."))


def off():
    was = agent.installed()
    agent.uninstall()
    print(t("tossling выключен.", "tossling is off.") if was else t("tossling и так выключен.", "tossling is already off."))


def remove():
    off()
    remove_quick_actions()
    if not agent.bundled:
        shutil.rmtree(agent.app, ignore_errors=True)
    for path in (CONFIG, agent.state_file):
        try:
            os.remove(path)
        except OSError:
            pass
    print(t("Помощник и настройки удалены (ключ тоже — телефон придётся подключать заново).", "The helper and its settings are removed (the key too: the phone has to pair again)."))


def pause():
    minutes = int(args[1]) if len(args) > 1 and args[1].isdigit() else 30
    until = time.time() + minutes * 60
    write_conf(paused_until=until)
    print(t(f"{OK} не отправляю буфер на телефон до {datetime.fromtimestamp(until):%H:%M}. " f"С телефона на Mac — по-прежнему. Раньше: tossling resume", f"{OK} not sending the clipboard to the phone until {datetime.fromtimestamp(until):%H:%M}. " f"From the phone to the Mac still works. Earlier: tossling resume"))


def resume():
    write_conf(paused_until=0)
    print(t(f"{OK} снова отправляю буфер на телефон.", f"{OK} sending the clipboard to the phone again."))


def images():
    if len(args) < 2 or args[1] not in ("on", "off"):
        sys.exit("tossling images on|off")
    write_conf(images=args[1] == "on")
    print(t(f"{OK} картинки {'передаю' if args[1] == 'on' else 'не передаю, только текст'}.", f"{OK} images {'are sent' if args[1] == 'on' else 'are not sent, text only'}."))


def auto():
    if len(args) < 2 or args[1] not in ("on", "off"):
        sys.exit("tossling auto on|off")
    write_conf(auto=args[1] == "on")
    if args[1] == "on":
        print(t(f"{OK} скопированные текст и картинки уходят сами. Файлы — только по горячей клавише или «Отправить в Tossling».", f"{OK} copied text and images go by themselves. Files only on the hotkey or «Send via Tossling»."))
    else:
        print(t(f"{OK} по ⌘C ничего не уходит. Отправить буфер: горячая клавиша или «Отправить в Tossling» в Finder.", f"{OK} nothing goes on ⌘C. To send the clipboard: the hotkey or «Send via Tossling» in Finder."))


def hotkey():
    if len(args) < 2 or args[1] not in ("on", "off"):
        sys.exit("tossling hotkey on|off")
    write_conf(hotkey=args[1] == "on")
    apply_names()
    print(t(f"{OK} горячая клавиша {'отправляет буфер' if args[1] == 'on' else 'больше не занята Tossling'}.", f"{OK} the hotkey {'sends the clipboard' if args[1] == 'on' else 'is no longer taken by Tossling'}."))


SERVICES = os.path.join(HOME, "Library", "Services")
QUICK_ACTION_TITLES = {"ru": ("Отправить в Tossling", "Отправить в Tossling…"), "en": ("Send via Tossling", "Send via Tossling…")}


def quick_actions():
    return tuple(zip(QUICK_ACTION_TITLES[t("ru", "en")], ("--notify", "--notify --pick")))


def all_quick_action_titles():
    return [title for titles in QUICK_ACTION_TITLES.values() for title in titles]


def quick_action_files(title, command):
    info = {"NSServices": [{
        "NSBackgroundColorName": "background", "NSIconName": "NSActionTemplate",
        "NSMenuItem": {"default": title}, "NSMessage": "runWorkflowAsService",
        "NSRequiredContext": {"NSApplicationIdentifier": "com.apple.finder"}, "NSSendFileTypes": ["public.item"],
    }]}
    arg = lambda i, name, default: {"default value": default, "name": name, "required": "0", "type": "0", "uuid": str(i)}
    action = {
        "AMAccepts": {"Container": "List", "Optional": True, "Types": ["com.apple.cocoa.path"]},
        "AMActionVersion": "2.0.3", "AMApplication": ["Automator"],
        "AMParameterProperties": {k: {} for k in ("COMMAND_STRING", "CheckedForUserDefaultShell", "inputMethod", "shell", "source")},
        "AMProvides": {"Container": "List", "Types": ["com.apple.cocoa.string"]},
        "ActionBundlePath": "/System/Library/Automator/Run Shell Script.action", "ActionName": "Run Shell Script",
        "ActionParameters": {"COMMAND_STRING": command, "CheckedForUserDefaultShell": True, "inputMethod": 1,
                             "shell": "/bin/zsh", "source": ""},
        "BundleIdentifier": "com.apple.RunShellScript", "CFBundleVersion": "2.0.3",
        "CanShowSelectedItemsWhenRun": False, "CanShowWhenRun": True, "Category": ["AMCategoryUtilities"],
        "Class Name": "RunShellScriptAction",
        "InputUUID": "6E0A3C9D-3D2B-4C1E-9F0A-1A2B3C4D5E61", "OutputUUID": "6E0A3C9D-3D2B-4C1E-9F0A-1A2B3C4D5E62",
        "UUID": "6E0A3C9D-3D2B-4C1E-9F0A-1A2B3C4D5E63", "UnlocalizedApplications": ["Automator"],
        "arguments": {"0": arg(0, "inputMethod", 0), "1": arg(1, "CheckedForUserDefaultShell", False),
                      "2": arg(2, "source", ""), "3": arg(3, "COMMAND_STRING", ""), "4": arg(4, "shell", "/bin/sh")},
        "conversionLabel": 0, "isViewVisible": 1, "location": "309.000000:305.000000",
        "nibPath": "/System/Library/Automator/Run Shell Script.action/Contents/Resources/Base.lproj/main.nib",
    }
    document = {
        "AMApplicationBuild": "534", "AMApplicationVersion": "2.10", "AMDocumentVersion": "2",
        "actions": [{"action": action, "isViewVisible": 1}], "connectors": {},
        "workflowMetaData": {
            "applicationBundleIDsByPath": {}, "applicationPaths": [],
            "inputTypeIdentifier": "com.apple.Automator.fileSystemObject",
            "outputTypeIdentifier": "com.apple.Automator.nothing", "presentationMode": 15, "processesInput": False,
            "serviceInputTypeIdentifier": "com.apple.Automator.fileSystemObject",
            "serviceOutputTypeIdentifier": "com.apple.Automator.nothing", "serviceProcessesInput": False,
            "systemImageName": "NSActionTemplate", "useAutomaticInputType": False,
            "workflowTypeIdentifier": "com.apple.Automator.servicesMenu",
        },
    }
    return plistlib.dumps(info), plistlib.dumps(document)


def install_quick_actions():
    import finder_ext
    icon = os.path.join(agent.app, "Contents", "Resources", "AppIcon.icns")
    error = finder_ext.install(icon=icon)
    if error is None:
        if any(os.path.exists(os.path.join(SERVICES, f"{title}.workflow")) for title in all_quick_action_titles()):
            remove_services()
        return
    print(t(f"{WARN} пункт в меню Finder не собрался, ставлю быстрые действия: {error.splitlines()[0] if error else ''}", f"{WARN} the Finder menu item did not build, installing Quick Actions: {error.splitlines()[0] if error else ''}"))
    tossling = shutil.which("tossling") or os.path.join(ROOT, "bin", "tossling")
    changed = False
    current = [title for title, _ in quick_actions()]
    for title in all_quick_action_titles():
        if title not in current and os.path.exists(os.path.join(SERVICES, f"{title}.workflow")):
            shutil.rmtree(os.path.join(SERVICES, f"{title}.workflow"), ignore_errors=True)
            changed = True
    for title, flags in quick_actions():
        contents = os.path.join(SERVICES, f"{title}.workflow", "Contents")
        files = dict(zip(("Info.plist", "document.wflow"), quick_action_files(title, f'"{tossling}" send {flags} "$@"')))
        for name, data in files.items():
            path = os.path.join(contents, name)
            try:
                with open(path, "rb") as f:
                    if f.read() == data:
                        continue
            except OSError:
                pass
            os.makedirs(contents, exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)
            changed = True
    if enable_quick_actions() or changed:
        subprocess.run(["/System/Library/CoreServices/pbs", "-update"], capture_output=True)


def enable_quick_actions():
    r = subprocess.run(["defaults", "export", "pbs", "-"], capture_output=True)
    try:
        prefs = plistlib.loads(r.stdout) if r.returncode == 0 and r.stdout else {}
    except Exception:
        prefs = {}
    status = prefs.setdefault("NSServicesStatus", {})
    want = {"enabled_context_menu": True, "enabled_services_menu": True,
            "presentation_modes": {"ContextMenu": True, "ServicesMenu": True}}
    changed = False
    for title, _ in quick_actions():
        key = f"(null) - {title} - runWorkflowAsService"
        if status.get(key) != want:
            status[key] = want
            changed = True
    if changed:
        subprocess.run(["defaults", "import", "pbs", "-"], input=plistlib.dumps(prefs), capture_output=True)
    return changed


def remove_quick_actions():
    import finder_ext
    finder_ext.remove()
    remove_services()


def remove_services():
    for title in all_quick_action_titles():
        shutil.rmtree(os.path.join(SERVICES, f"{title}.workflow"), ignore_errors=True)
    subprocess.run(["/System/Library/CoreServices/pbs", "-update"], capture_output=True)


def refresh_helper():
    if agent.bundled or cmd in ("off", "remove", "setup", "on") or not configured():
        return
    s = agent.state()
    if not s and not agent.installed():
        return
    stamp = open(agent.stamp).read().strip() if os.path.exists(agent.stamp) else ""
    try:
        rebuilt = bool(s) and os.path.getmtime(agent.bin) > (s.get("started") or 0) + 1
    except OSError:
        rebuilt = False
    if agent.source_hash() == stamp and not rebuilt:
        return
    print(paint(t("обновляю помощника: он работал на прежней версии", "updating the helper: it was running the previous version"), "2"))
    listed = subprocess.run(["launchctl", "list"], capture_output=True, text=True).stdout.splitlines()
    for line in listed:
        label = line.split("\t")[-1]
        if label.startswith(agent.base + "."):
            agent.launchctl("bootout", f"{agent.domain}/{label}")
    if s:
        try:
            os.kill(s["pid"], 15)
        except OSError:
            pass
    agent.stop()
    agent.build()
    if agent.start() is not True:
        print(t(f"{WARN} новый помощник не запустился", f"{WARN} the new helper did not start") + paint("   -> tossling on", "2"))
    install_quick_actions()


def send():
    if not configured():
        sys.exit(not_configured())
    rest, targets, flags = [], [], []
    pick = False
    items = iter(args[1:])
    for a in items:
        if a == "--notify":
            flags.append(a)
        elif a == "--pick":
            pick = True
        elif a in ("--to", "-t"):
            targets.append(next(items, ""))
        elif a.startswith("--to="):
            targets.append(a[5:])
        else:
            rest.append(a)
    ids = []
    if targets:
        conf = read_conf()
        devices = room_devices(conf, agent.state() or {})
        for t in targets:
            low = t.strip().lower()
            found = [d for d in devices if low in (d["title"].lower(), d["name"].lower(), d["id"].lower())]
            if not found:
                names = ", ".join(d["title"] for d in devices) or t("в комнате пока никого", "nobody in the room yet")
                sys.exit(t(f"Нет устройства «{t}». Есть: {names}", f"No device «{t}». There are: {names}"))
            ids.append(found[0]["id"])
    if pick and not ids:
        chosen = pick_targets()
        if chosen is None:
            return
        ids = chosen
    piped = None
    if not rest and not sys.stdin.isatty():
        piped = sys.stdin.buffer.read()
    if not rest and not piped:
        sys.exit(t("tossling send [--to <кто>] <файл|текст>…  или  … | tossling send [--to <кто>]", "tossling send [--to <who>] <file|text>…  or  … | tossling send [--to <who>]"))
    agent.build()
    cmd_args = flags + [a for i in ids for a in ("--to", i)]
    tmp = None
    if piped is not None:
        os.makedirs(os.path.dirname(QR), exist_ok=True)
        try:
            piped.decode("utf-8")
            tmp = os.path.join(os.path.dirname(QR), f"tossling-send-{os.getpid()}.txt")
            cmd_args += ["--text", tmp]
        except UnicodeDecodeError:
            tmp = os.path.join(os.path.dirname(QR), f"tossling-send-{os.getpid()}", "stdin.bin")
            os.makedirs(os.path.dirname(tmp), exist_ok=True)
            cmd_args += ["--send", tmp]
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(piped)
    for a in rest:
        cmd_args += ["--send", a]
    try:
        code = subprocess.run([agent.bin, "--config", CONFIG, *cmd_args]).returncode
    finally:
        if tmp:
            try:
                os.remove(tmp)
                if tmp.endswith("stdin.bin"):
                    os.rmdir(os.path.dirname(tmp))
            except OSError:
                pass
    sys.exit(code)


def pick_targets():
    devices = room_devices(read_conf(), agent.state() or {})
    if len(devices) < 2:
        return []
    everyone = t("Все устройства", "All devices")
    quote = lambda v: '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'
    titles = [d["title"] for d in devices]
    script = (t(f'choose from list {{{", ".join(quote(t) for t in [everyone] + titles)}}} with title "Tossling" ' f'with prompt "Кому отправить?" default items {{{quote(everyone)}}} with multiple selections allowed', f'choose from list {{{", ".join(quote(t) for t in [everyone] + titles)}}} with title "Tossling" ' f'with prompt "Send to whom?" default items {{{quote(everyone)}}} with multiple selections allowed'))
    r = subprocess.run(["osascript", "-e", script], capture_output=True, text=True)
    picked = [p.strip() for p in r.stdout.strip().split(", ")] if r.returncode == 0 else []
    if not picked or picked == ["false"]:
        return None
    if everyone in picked:
        return []
    return [d["id"] for d in devices if d["title"] in picked]


def show_log():
    try:
        lines = open(agent.log, encoding="utf-8", errors="replace").read().splitlines()
    except OSError:
        sys.exit(t("Лога пока нет.", "No log yet."))
    for line in lines[-25:]:
        stamp, _, text = line.partition(" ")
        try:
            stamp = datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone().strftime("%d.%m %H:%M:%S")
        except ValueError:
            pass
        print(f"{paint(stamp, '2')}  {text}")


def ago(ts):
    return datetime.fromtimestamp(ts).strftime("%d.%m %H:%M") if ts else "—"


def status():
    conf = read_conf()
    if not configured():
        print(t("tossling не настроен.", "tossling is not set up.") + paint(t("   -> tossling setup <адрес сервера>", "   -> tossling setup <server address>"), "2"))
        return
    if not agent.installed():
        print(t("tossling выключен.", "tossling is off.") + paint("   -> tossling on", "2"))
        return
    s = agent.state()
    if not s:
        print(t(f"{BAD} tossling включён, но помощник не работает.", f"{BAD} tossling is on, but the helper is not running.") + paint("   -> tossling on", "2"))
        print(t(f"  лог: {tilde(agent.log)}", f"  log: {tilde(agent.log)}"))
        return
    link = t(f"{OK} на связи с {conf['server']}", f"{OK} connected to {conf['server']}") if s.get("connected") else t(f"{WARN} нет связи с {conf['server']}", f"{WARN} no connection to {conf['server']}")
    print(t(f"{link} (pid {s['pid']}, с {ago(s['started'])})", f"{link} (pid {s['pid']}, since {ago(s['started'])})"))
    print(t(f"  этот Mac: {my_name(conf)}", f"  this Mac: {my_name(conf)}"))
    others = room_devices(conf, s)
    if others:
        names = [t(f"{d['title']} ({own(d)}{'Mac' if d['mac'] else 'телефон'}, {ago(d['seen'])})", f"{d['title']} ({own(d)}{'Mac' if d['mac'] else 'phone'}, {ago(d['seen'])})") for d in others]
        print(t(f"  устройства в комнате: {', '.join(names)}", f"  devices in the room: {', '.join(names)}"))
    else:
        print(t(f"  телефон: {s.get('phone') or 'ещё не подключался — tossling pair'}", f"  phone: {s.get('phone') or 'not paired yet: tossling pair'}"))
    if not conf.get("room"):
        print(t(f"{WARN} старая схема без комнаты", f"{WARN} the old scheme without a room") + paint("   -> tossling on", "2"))
    print(t(f"  отправлено: {s.get('sent', 0)} (последнее {ago(s.get('last_sent'))}) · " f"получено: {s.get('received', 0)} (последнее {ago(s.get('last_received'))})", f"  sent: {s.get('sent', 0)} (last {ago(s.get('last_sent'))}) · " f"received: {s.get('received', 0)} (last {ago(s.get('last_received'))})"))
    until = conf.get("paused_until") or 0
    if until > time.time():
        print(t(f"{WARN} на паузе до {datetime.fromtimestamp(until):%H:%M}", f"{WARN} paused until {datetime.fromtimestamp(until):%H:%M}") + paint("   -> tossling resume", "2"))
    if not conf.get("images", True):
        print(t("  картинки не передаю (tossling images on)", "  images are not sent (tossling images on)"))
    if not conf.get("auto", True):
        print(t("  по ⌘C ничего не отправляю, только по горячей клавише (tossling auto on)", "  nothing is sent on ⌘C, only on the hotkey (tossling auto on)"))
    if not conf.get("hotkey", True):
        print(t("  горячая клавиша выключена (tossling hotkey on)", "  the hotkey is off (tossling hotkey on)"))


def computer_name():
    r = subprocess.run(["scutil", "--get", "ComputerName"], capture_output=True, text=True)
    return r.stdout.strip() or "Mac"


def my_name(conf):
    return conf.get("name") or computer_name()


def room_devices(conf, state):
    aliases = conf.get("aliases") or {}
    me = conf.get("device_id", "")
    out = []
    for key, m in (state.get("members") or {}).items():
        if key == me:
            continue
        name = m.get("name", "?")
        out.append({"id": key, "name": name, "title": aliases.get(key) or name, "mac": m.get("src") == "mac",
                    "seen": m.get("seen") or 0})
    return sorted(out, key=lambda d: -d["seen"])


def own(device):
    return t(f"своё имя «{device['name']}», ", f"its own name «{device['name']}», ") if device["title"] != device["name"] else ""


def apply_names():
    if agent.installed() and agent.state():
        agent.restart()
        agent.wait_state()


def rename_self(name):
    if name:
        write_conf(name=name)
    else:
        drop_conf("name")
    apply_names()
    print(t(f"{OK} этот Mac теперь «{my_name(read_conf())}» — имя увидят все устройства в комнате.", f"{OK} this Mac is now «{my_name(read_conf())}»: every device in the room sees the name."))


def rename_other(device, name):
    aliases = dict(read_conf().get("aliases") or {})
    if name and name != device["name"]:
        aliases[device["id"]] = name
    else:
        aliases.pop(device["id"], None)
    if aliases:
        write_conf(aliases=aliases)
    else:
        drop_conf("aliases")
    apply_names()
    if device["id"] in aliases:
        print(t(f"{OK} «{device['name']}» на этом Mac называется «{aliases[device['id']]}». На других устройствах имя не меняется.", f"{OK} «{device['name']}» is called «{aliases[device['id']]}» on this Mac. Other devices keep its name."))
    else:
        print(t(f"{OK} «{device['name']}» снова называется своим именем.", f"{OK} «{device['name']}» goes by its own name again."))


def rename():
    if not configured():
        sys.exit(not_configured())
    conf = read_conf()
    others = room_devices(conf, agent.state() or {})
    if len(args) == 2:
        rename_self(args[1].strip())
        return
    if len(args) >= 3:
        wanted = args[1].strip().lower()
        found = [d for d in others if wanted in (d["title"].lower(), d["name"].lower(), d["id"].lower())]
        if not found:
            sys.exit(t(f"Нет устройства «{args[1]}» в комнате. Список: tossling status", f"No device «{args[1]}» in the room. The list: tossling status"))
        rename_other(found[0], " ".join(args[2:]).strip())
        return
    if not sys.stdin.isatty():
        sys.exit(t("tossling rename <имя> | tossling rename <кто> <имя>", "tossling rename <name> | tossling rename <who> <name>"))
    from menu import pick
    rows = [(t(f"{my_name(conf)} этот mac", f"{my_name(conf)} this mac"), t(f"{my_name(conf)}  {paint('этот Mac · имя видят все', '2')}", f"{my_name(conf)}  {paint('this Mac · everyone sees the name', '2')}"))]
    for d in others:
        note = "Mac" if d["mac"] else t("телефон", "phone")
        if d["title"] != d["name"]:
            note += t(f" · сам называет себя «{d['name']}»", f" · calls itself «{d['name']}»")
        rows.append((f"{d['title']} {d['name']}", t(f"{d['title']}  {paint(note + ' · имя только на этом Mac', '2')}", f"{d['title']}  {paint(note + ' · the name is only on this Mac', '2')}")))
    i = pick(rows, title=t("\x1b[1;35mКого переименовать?\x1b[0m", "\x1b[1;35mRename whom?\x1b[0m"), esc=t("назад", "back"))
    if i is None:
        return
    try:
        name = input(t("Новое имя (пусто — прежнее): ", "New name (empty: the previous one): ")).strip()
    except (KeyboardInterrupt, EOFError):
        print()
        return
    if i == 0:
        rename_self(name)
    else:
        rename_other(others[i - 1], name)


def language():
    if len(args) < 2 or args[1] not in ("ru", "en", "auto"):
        sys.exit("tossling language ru|en|auto")
    if args[1] == "auto":
        drop_conf("language")
    else:
        write_conf(language=args[1])
    apply_names()
    print({"ru": f"{OK} теперь по-русски.", "en": f"{OK} English from now on."}.get(args[1], f"{OK} auto: the system language."))


def project():
    if not configured():
        sys.exit(not_configured())
    conf = read_conf()
    base = conf["server"].rstrip("/")
    action = args[1] if len(args) > 1 else "list"
    rest = args[2:]
    publisher = ""
    if "--publisher" in rest:
        i = rest.index("--publisher")
        publisher = rest[i + 1] if i + 1 < len(rest) else ""
        rest = rest[:i] + rest[i + 2:]

    def answer(code, raw):
        if code == 404 and not raw.strip().startswith(b"{\"error\""):
            sys.exit(t("Сервер не Tossling Server: проекты на нём заводятся вручную.", "The server is not a Tossling Server: create projects on it by hand."))
        if code == 0:
            sys.exit(t(f"{BAD} нет связи с сервером: {error_text(raw)}", f"{BAD} no connection to the server: {error_text(raw)}"))
        if code >= 300:
            sys.exit(f"{BAD} HTTP {code} {error_text(raw)}")
        return json.loads(raw) if raw.strip() else None

    if action == "list":
        projects = answer(*api("GET", base, "projects", conf["token"]))
        if not projects:
            print(t("Проектов пока нет: tossling project add <канал>", "No projects yet: tossling project add <channel>"))
        for p in projects:
            who = p.get("publisher") or t("пишут устройства", "written by the devices")
            print(f"{p['topic']:<24} {p.get('name', ''):<24} {paint(who, '2')}")
    elif action == "add" and rest:
        body = json.dumps({"topic": rest[0], "name": " ".join(rest[1:]), "publisher": publisher}).encode()
        p = answer(*api("POST", base, "projects", conf["token"], body, {"Content-Type": "application/json"}))
        print(t(f"{OK} проект «{p['name']}» в канале {p['topic']}, отправитель {p.get('publisher', '')}",
                f"{OK} project «{p['name']}» on channel {p['topic']}, publisher {p.get('publisher', '')}"))
        if p.get("token"):
            print(t("Токен отправителя показывается один раз — пропиши его в настройках сервиса:",
                    "The publisher token is shown once: put it into the service settings now:"))
            print(f"\n    {p['token']}\n\n    {p.get('example', '')}\n")
        else:
            print(t("Отправитель пишет сюда своим прежним токеном.", "The publisher writes here with the token it already has."))
    elif action == "remove" and len(rest) == 1:
        answer(*api("DELETE", base, f"projects/{rest[0]}", conf["token"]))
        print(t(f"{OK} проект {rest[0]} удалён: писать в него больше никто не может.",
                f"{OK} project {rest[0]} removed: nobody can publish there any more."))
    else:
        sys.exit("tossling project [list] | add <channel> [name] [--publisher <name>] | remove <channel>")


def show_config():
    if "--json" not in args:
        sys.exit("tossling config --json")
    conf = legacy.read_config()
    if not (conf.get("server") and conf.get("token")):
        print("{}")
        sys.exit(1)
    print(json.dumps({k: conf[k] for k in ("server", "token", "alert_topics") if k in conf}))


MENU = (("status", t("состояние", "status")), ("rename", t("переименовать устройство", "rename a device")), ("invite", t("код для второго Mac", "a code for another Mac")),
        ("join", t("подключить этот Mac к комнате другого", "join the room of another Mac")), ("pair", t("показать QR для телефона", "QR code for a phone")),
        ("setup", t("подключить сервер", "connect a server")), ("pause", t("не отправлять 30 минут", "do not send for 30 minutes")), ("resume", t("снова отправлять", "send again")),
        ("auto off", t("отправлять только по горячей клавише", "send only on the hotkey")), ("auto on", t("отправлять сам по ⌘C", "send by itself on ⌘C")), ("log", t("что передано", "what was sent")),
        ("on", t("включить", "start")), ("off", t("выключить", "stop")))


def menu():
    from menu import pick
    tossling = os.path.join(ROOT, "bin", "tossling")
    at = 0
    while True:
        rows = [(f"{action} {note}", f"{action:<10} {paint(note, '2')}") for action, note in MENU]
        at = pick(rows, title="Tossling", default=at)
        if at is None:
            return
        action = MENU[at][0].split()
        if action == ["join"]:
            try:
                code = input(t("Код с другого Mac (tossling.example.com/ABCD-EFGH): ", "Code from the other Mac (tossling.example.com/ABCD-EFGH): ")).strip()
            except (KeyboardInterrupt, EOFError):
                print()
                continue
            if not code:
                continue
            action.append(code)
        print(paint("$ tossling " + " ".join(action), "2"))
        try:
            subprocess.run([tossling, *action])
        except KeyboardInterrupt:
            pass
        print()


if cmd == "config":
    show_config()
    sys.exit(0)
if cmd == "adopt":
    import finder_ext
    migrated = legacy.migrate() is not None
    if legacy.remove_older_installs() or migrated:
        remove_services()
        finder_ext.install()
    sys.exit(0)
moved_from = legacy.TOSSY_CONFIG_DIR if legacy.tossy_pending() else legacy.MNRH_CONFIG
was_running = legacy.migrate()
if was_running is not None:
    print(t(f"{OK} настройки перенесены из {tilde(moved_from)} в {tilde(CONFIG_DIR if moved_from == legacy.TOSSY_CONFIG_DIR else CONFIG)}", f"{OK} settings moved from {tilde(moved_from)} to {tilde(CONFIG_DIR if moved_from == legacy.TOSSY_CONFIG_DIR else CONFIG)}"))
    if was_running and configured() and cmd not in ("off", "remove"):
        start()
if not args and sys.stdin.isatty() and sys.stdout.isatty() and not (os.environ.get("TOSSLING_NO_MENU") or os.environ.get("TOSSY_NO_MENU")):
    status()
    print()
    menu()
    sys.exit(0)
refresh_helper()
{"status": status, "auto": auto, "hotkey": hotkey, "rename": rename, "setup": setup, "pair": pair, "invite": invite, "join": join, "on": on, "off": off, "pause": pause, "resume": resume,
 "send": send, "images": images, "log": show_log, "remove": remove, "language": language, "project": project}[cmd]()
