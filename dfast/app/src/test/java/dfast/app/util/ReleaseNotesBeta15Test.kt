package dfast.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored body of the published v1.0.1-beta.15 release (as the GitHub API returns it) as a fixture. */
class ReleaseNotesBeta15Test {
    private val body = "Signed beta build of DFast.\n\n* Betas attach only the `arm64-v8a` APK (signed). There is no universal, armeabi-v7a, x86 or x86_64 APK in a beta.\n* Verify the download with the bundled `SHA256SUMS.txt`.\n\n<!-- whatsnew:ar -->\n- تمت إزالة اهتزاز الإيقاع نهائياً لراحة العين.\n- إصلاح فتح قوائم التشغيل من مكتبتك: تفتح الآن محتوى القائمة بدل شاشة البحث.\n- انسياب ألوان الغلاف ببطء في خلفية المشغّل، أنشط في الأغاني العالية وأهدأ في الهادئة (له مفتاح في الإعدادات).\n- أعمدة الصوت الرفيعة تحت الحلقة بألوان الغلاف (لها مفتاح في الإعدادات).\n- إشعار تحديث جديد بهوية DFast يعرض ما الجديد بلغتك.\n<!-- /whatsnew -->\n<!-- whatsnew:en -->\n- The beat shake is removed for good, for eye comfort.\n- Library playlists now open their songs instead of an empty search screen.\n- Slow colour flow in the player background from the cover colours, livelier in loud songs and calmer in quiet ones (own switch in settings).\n- Thin sound bars under the ring in the cover colours (own switch in settings).\n- A new update notice in DFast style that shows what's new in your language.\n<!-- /whatsnew -->\n\n\n**Full Changelog**: https://github.com/MenuM7D/dfast/compare/v1.0.1-beta.14...v1.0.1-beta.15"

    @Test fun arabicPicksArabicOnly() { for (l in listOf("ar", "ar-EG", "arz", "egy")) { val r = ReleaseNotes.pick(body, l); assertTrue(l, r.contains("تمت إزالة")); assertFalse(l, r.contains("beat shake")); assertFalse(l, r.contains("Signed beta")) } }
    @Test fun englishPicksEnglishOnly() { for (l in listOf("en", "en-US")) { val r = ReleaseNotes.pick(body, l); assertTrue(l, r.contains("beat shake")); assertFalse(l, r.contains("تمت إزالة")) } }
    @Test fun othersFallBackToEnglish() { for (l in listOf("ja", "pt-BR", "fr")) { val r = ReleaseNotes.pick(body, l); assertTrue(l, r.contains("beat shake")); assertFalse(l, r.contains("تمت إزالة")) } }
}
