# DFast 1.0.0 Release Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** إعادة ضبط إصدار التطبيق إلى `1.0.0`، وحذف الملفات الميتة/الثقيلة من المستودع، وتجهيز مفاتيح التوقيع الرسمية كـ GitHub Actions secrets + variables، بحيث ينتج CI نسخة Debug ونسخ Release موقّعة ومنشورة فعليًا.

**Architecture:** لا تغيير في بنية التطبيق. العمل كله في ثلاثة محاور: (1) أرقام الإصدار داخل `app/build.gradle` الذي يبني منها `versionCode`/`versionName`/أسماء ملفات APK، (2) تقليم مستودع الـ fork من مخرجات بناء قديمة وscratch وCI ميت بحيث يبقى كل ما هو مستخدم فعليًا، (3) عمود فقري واحد للتوقيع: keystore واحد يُخزَّن base64 في `SIGNING_KEY` ويُفكّ في job التوقيع ثم يُشارك مع job الإصدار عبر artifact، وهو نفس المسار الذي يقرأه `app/build.gradle` من `keystore.properties`.

**Tech Stack:** Gradle 8.13 / AGP 8.13.2 / Kotlin 2.3، GitHub Actions (`actions/checkout@v4`, `setup-java@v4`, `upload-artifact@v4`, `softprops/action-gh-release@v2`)، `gh` CLI، `openssl` لتوليد PKCS#12، GitHub Secrets API (sealed box / libsodium)، PyNaCl للتشفير.

**Spec:** طلب المستخدم في محادثة 2026-11-24 (بنود 1، 3، 4، 5) + الـ workflow القائم في `.github/workflows/build.yml` (commit `73f5bae`).

## Global Constraints

- لا تشغيل Gradle/JDK/Android SDK داخل الـ sandbox (خطر OOM). كل بناء فعلي على GitHub runners.
- المشروع في مجلد `dfast/`؛ كل خطوات Gradle تستخدم `working-directory: dfast`.
- `applicationId` = `com.deniscerri.ytdl` (مشترك مع upstream) — لا يُغيَّر.
- `versionName` = `1.0.0` بالضبط، و`versionCode` = `1000000` (يحسبه Gradle: `major*1000000 + minor*10000 + patch*100 + build`).
- أسماء الـ secrets ثابتة ومطابقة للـ workflow: `SIGNING_KEY`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`.
- لا تُكتب أي قيمة سرية في الملفات المتتبعة، ولا في المحادثة، ولا في أي مخرَج أمر.
- الـ keystore المتولَّد يُحفظ بـ PKCS#12 (`.jks`) مع `KEY_PASSWORD == KEYSTORE_PASSWORD` (قيد PKCS#12 في Java).
- حذف الملفات لا يمسّ: كود التطبيق، الموارد (layout/drawable/values/الأيقونات داخل `app/src/main/res`)، `gradlew`، `app/schemas/` (مطلوبة لـ Room migrations)، ملفات الترجمة README-*، `fastlane/` (بيانات نشر المتجر)، `LICENSE`, `SECURITY.md`, `CONTRIBUTING.MD`.

## Review Focus

- **تركيب فوق نسخة قديمة:** `versionCode` = 1000000 أدنى من 109010100 في upstream، فالتثبيت فوق نسخة upstream سيُرفض؛ المتوقع أن يفشل التثبيت فوق القديم (`INSTALL_FAILED_VERSION_DOWNGRADE`) والحل إزالة النسخة القديمة أولًا. حل التوقيع الجديد مختلف أصلًا فيفرض إزالة.
- **keystore بصيغة PKCS#12 وكلمتا مرور مختلفتان:** Java يقبل PKCS#12 فقط عندما تكون كلمة مرور المفتاح مساوية لكلمة مرور المخزن؛ أي اختلاف يجب أن يفشل بوضوح في `keytool -list` داخل job التوقيع لا في منتصف البناء.
- **base64 متعدد الأسطر في `SIGNING_KEY`:** `base64 -d` يفشل أو ينتج ملفًا تالفًا إن احتفظ السر بأسطر جديدة؛ المتوقع أن يبقى السر سطرًا واحدًا وأن يكتشف الـ workflow الملف التالف قبل البناء.
- **سر ناقص (مثل `KEY_ALIAS`):** يجب أن يستمر البناء بقيمة افتراضية معقولة (`release`) بدل فشل غامض، مع تحذير واضح.
- **إصدار مكرر:** تشغيل Release مرتين على نفس الوسم يجب أن يحدّث الأصول (`--clobber`-style) بدل الفشل.

---

### Task 1: إعادة ضبط الإصدار إلى 1.0.0

**Files:**
- Modify: `dfast/app/build.gradle:11-14`
- Modify: `dfast/CHANGELOG.md:1-2` (إضافة قسم جديد أعلى الملف)
- Modify: `.github/workflows/build.yml:53` (مثال الوسم في وصف المدخل)

**Interfaces:**
- Consumes: لا شيء.
- Produces: `versionName == "1.0.0"`, `versionCode == 1000000`, أسماء ملفات `YTDLnis-1.0.0-<abi>-<buildType>-<flavor>.apk`; الـ workflow يستخرج `major/minor/patch` بـ `sed` من نفس الأسطر ليصنع الوسم `v1.0.0`.

- [ ] **Step 1: تعديل أرقام الإصدار**

في `dfast/app/build.gradle`:
```groovy
def versionMajor = 1
def versionMinor = 0
def versionPatch = 0
def versionBuild = 0 // bump for dogfood builds, public betas, etc.
```
(`versionBuild = 0` يجعل `versionExt` فارغًا، فلا لاحقة `.0` في الاسم.)

- [ ] **Step 2: التحقق من الحساب دون Gradle**

Run:
```bash
awk -F'= ' '/^def version(Major|Minor|Patch|Build)/ {gsub(/[^0-9/]/,"",$2); v[$1]=$2} END {code=v["def versionMajor"]*1000000+v["def versionMinor"]*10000+v["def versionPatch"]*100+v["def versionBuild"]; print "versionCode=" code; exit !(code==1000000)}' dfast/app/build.gradle
```
Expected: `versionCode=1000000` و exit 0.

- [ ] **Step 3: قسم CHANGELOG أعلى `dfast/CHANGELOG.md`**

بعد سطر `# YTDLnis Changelog` يُضاف:
```markdown
> # 1.0.0 (2026-11)

# DFast 1.0.0

أول إصدار من fork ‏DFast، يُبنى ويُوقَّع وينتشر آليًا عبر GitHub Actions.

- إعادة ترقيم الإصدار إلى 1.0.0 (versionCode 1000000) لهذا الـ fork
- نسخة Debug على كل push، ونسخ Release موقّعة على وسوم `v*` أو تشغيل يدوي
- نشر الأصول مع `SHA256SUMS.txt` للتحقق من التنزيل
```

- [ ] **Step 4: تحديث مثال الوسم في الـ workflow**

`.github/workflows/build.yml` — الوصف:
```yaml
        description: 'Release tag, e.g. v1.0.0 (leave empty to derive one)'
```

- [ ] **Step 5: Commit**

```bash
git add dfast/app/build.gradle dfast/CHANGELOG.md .github/workflows/build.yml
git commit -m "chore: reset the DFast fork to version 1.0.0"
```

---

### Task 2: حذف الملفات غير المستخدمة وتحصين `.gitignore`

**Files:**
- Delete: `dfast-1.9.1 (4).apk` (88 MB — مخرَج بناء قديم في جذر المستودع)
- Delete: `ytdlnis-1.9.1.1.zip` (13 MB — أرشيف مصدر لا يستخدمه أي شيء)
- Delete: `study/*.png` (10 صور أيقونات scratch، الصور الفعلية في `dfast/app/src/main/res/mipmap-*`)
- Delete: `dfast/.github/workflows/{android.yml,tg-bot.yml,update-translators.yml}` (GitHub لا يقرأ `.github` المتداخل؛ و`build.yml` الجذري يحلّ محلها)
- Delete: `dfast/scripts/fetch_translators.py` (مستهلكه الوحيد هو `update-translators.yml`)
- Modify: `dfast/.gitignore` (منع تتبّع أي keystore)

**Interfaces:**
- Consumes: لا شيء.
- Produces: مستودع بلا أصول بناء/scratch؛ `dfast/.gitignore` يمنع `*.jks` و`*.keystore` من أي commit مستقبلي.

- [ ] **Step 1: التحقق من أن أيقونات `study/` مكررة وغير مرجعية**

Run:
```bash
grep -rn "study/" --include="*.kt" --include="*.xml" --include="*.gradle" --include="*.yml" . | head
```
Expected: لا نتائج.

- [ ] **Step 2: الحذف**

```bash
git rm -q "dfast-1.9.1 (4).apk" ytdlnis-1.9.1.1.zip
git rm -q -r study
git rm -q dfast/.github/workflows/android.yml dfast/.github/workflows/tg-bot.yml \
          dfast/.github/workflows/update-translators.yml dfast/scripts/fetch_translators.py
```

- [ ] **Step 3: تحصين `.gitignore`**

في `dfast/.gitignore` بعد `/keystore.properties`:
```
*.jks
*.keystore
```

- [ ] **Step 4: التحقق**

Run:
```bash
git status --short && du -sh . && git ls-files | wc -l
```
Expected: الملفات المحذوفة في `git status` كـ `D`، وحجم مجلد العمل أقل بكثير من ~102 MB، و`git ls-files` ≈ 933.

- [ ] **Step 5: Commit**

```bash
git add -A dfast/.gitignore && git commit -m "chore: drop build artifacts, scratch icons and dead nested CI"
```

---

### Task 3: توليد keystore الإصدار ورفع الأسرار

**Files:**
- No repo file changes (الأسرار تعيش في GitHub فقط؛ التوليد في `/tmp` خارج المستودع)
- Modify: `.github/workflows/build.yml` (استخدام `vars.RELEASE_FLAVORS` كافتراضي)

**Interfaces:**
- Consumes: `GITHUB_GET_A_REPOSITORY_PUBLIC_KEY` (owner=`MenuM7D`, repo=`dfast`).
- Produces: repository secrets `SIGNING_KEY` (base64 سطر واحد لـ PKCS#12)، `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`؛ repository variable `RELEASE_FLAVORS=github`.

- [ ] **Step 1: توليد keystore في `/tmp` (بلا طباعة أي قيمة)**

```bash
work="$(mktemp -d)"; umask 077
openssl rand -base64 24 | tr -d '\n' > "$work/pass.txt"
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 10950 \
  -subj "/C=US/O=DFast/OU=Android/CN=DFast Release" \
  -keyout "$work/key.pem" -out "$work/cert.pem" >/dev/null 2>&1
openssl pkcs12 -export -inkey "$work/key.pem" -in "$work/cert.pem" \
  -name dfast -out "$work/release-keystore.jks" -passout "file:$work/pass.txt"
base64 -w0 "$work/release-keystore.jks" > "$work/key.b64"
```

- [ ] **Step 2: التحقق من صلاحية الـ keystore**

Run:
```bash
openssl pkcs12 -in "$work/release-keystore.jks" -passin "file:$work/pass.txt" -noout -info 2>&1 | grep -c "MAC"
```
Expected: `1`.

- [ ] **Step 3: تشفير القيم sealed-box (PyNaCl) ورفع الأسرار**

```bash
python3 -m pip install --quiet --user pynacl
```
ثم سكربت يقرأ `key_id`/`key` من API العام، ويشفّر كل قيمة، ويطبع **ciphertext فقط** لكل سر:
```
SIGNING_KEY       = base64(key.b64 مضغوطًا بلا أسطر جديدة)
KEY_ALIAS         = dfast
KEYSTORE_PASSWORD = محتوى pass.txt
KEY_PASSWORD      = نفس KEYSTORE_PASSWORD
```
`GITHUB_CREATE_OR_UPDATE_A_REPOSITORY_SECRET` لكل سر بـ `encrypted_value` + `key_id`.

- [ ] **Step 4: رفع الـ variable وربطه بالـ workflow**

`GITHUB_UPDATE_A_REPOSITORY_VARIABLE` (name=`RELEASE_FLAVORS`, value=`github`)، وفي `build.yml`:
```yaml
      FLAVORS_INPUT: ${{ github.event_name == 'workflow_dispatch' && inputs.flavors || vars.RELEASE_FLAVORS || 'github' }}
```

- [ ] **Step 5: حذف ملفات المفاتيح المؤقتة**

Run: `rm -rf "$work"` ثم `ls /tmp | grep -c tmp.` (لا حاجة لنتيجة محددة؛ الشرط أن `$work` غير موجود).

---

### Task 4: التحقق النهائي والنشر

**Files:**
- Modify: none.

- [ ] **Step 1: تحقق الـ YAML والـ bash**

```bash
python3 - <<'PY'
import yaml,sys
d=yaml.safe_load(open('.github/workflows/build.yml'))
assert set(d['jobs'])=={'debug','signing','release'}
print('yaml ok', list(d['jobs']))
PY
```
ثم `bash -n` على كل بلوك `run` (نفس طريقة التحقق السابقة).

- [ ] **Step 2: تأكيد الأسرار عبر الـ API (أسماء فقط)**

`GITHUB_LIST_REPOSITORY_SECRETS` → المتوقع 4 أسماء، و`GITHUB_LIST_REPOSITORY_VARIABLES` → `RELEASE_FLAVORS`.

- [ ] **Step 3: commit + push**

```bash
git commit -m "ci: read the default release flavors from repository variables"
git push origin HEAD:main
```

- [ ] **Step 4: تأكيد أن مسار Debug ما زال يعمل**

متابعة run الـ push عبر `GITHUB_LIST_JOBS_FOR_A_WORKFLOW_RUN` (owner=`MenuM7D`, repo=`dfast`) والتأكد من `Debug APK` = success و`Signed release APKs` = skipped.
