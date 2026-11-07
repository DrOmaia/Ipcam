package com.dromaia.ipcam

import android.content.Context

/** «How to add it»: short numbered steps for each make, shown on the add-camera screen under the camera type. */
object Guides {
    fun text(c: Context, b: Brand): String = when (b) {
        Brand.HIKVISION -> L.t(
            c,
            "1. العنوان: اضغط «بحث في الشبكة»، أو من تطبيق Hik-Connect: الجهاز ← الإعدادات ← معلومات الجهاز.\n" +
                "2. المستخدم admin، وكلمة المرور هي كلمة مرور الجهاز نفسه (التي وضعتها عند تفعيله)، وليست كلمة مرور حساب Hik-Connect.\n" +
                "3. مسجّل DVR / NVR: اكتب عنوان المسجّل ثم اضغط «إضافة كل كاميرات المسجّل»، أو اكتب رقم القناة لكل كاميرا.\n" +
                "4. للتنبيهات: في إعدادات الحركة بالجهاز فعّل «إبلاغ مركز المراقبة» (Notify Surveillance Center).",
            "1. Address: press “Search network”, or in the Hik-Connect app: device → Settings → Device information.\n" +
                "2. User admin, and the password is the device's own password (set when you activated it), not your Hik-Connect account.\n" +
                "3. DVR / NVR recorder: type the recorder's address, then press “Add all the recorder's cameras”, or type the channel of each camera.\n" +
                "4. For alerts: in the device's motion settings turn on “Notify Surveillance Center”.",
        )
        Brand.DAHUA -> L.t(
            c,
            "1. العنوان: اضغط «بحث في الشبكة»، أو من تطبيق Imou Life أو DMSS: إعدادات الجهاز ← معلومات الجهاز أو الشبكة.\n" +
                "2. Dahua وAmcrest: المستخدم admin وكلمة مرور الجهاز.\n" +
                "3. Imou: المستخدم admin، وكلمة المرور هي Safety Code المكتوب على ملصق الكاميرا (إلا إذا غيّرتها من التطبيق).\n" +
                "4. مسجّل Dahua: اكتب عنوان المسجّل ورقم القناة لكل كاميرا.",
            "1. Address: press “Search network”, or in the Imou Life or DMSS app: device settings → Device info or Network.\n" +
                "2. Dahua and Amcrest: user admin and the device password.\n" +
                "3. Imou: user admin, and the password is the Safety Code on the camera's label (unless you changed it in the app).\n" +
                "4. Dahua recorder: type the recorder's address and the channel of each camera.",
        )
        Brand.EZVIZ -> L.t(
            c,
            "1. العنوان: اضغط «بحث في الشبكة»، أو من تطبيق EZVIZ: الكاميرا ← الإعدادات ← معلومات الجهاز.\n" +
                "2. المستخدم admin، وكلمة المرور هي «رمز التحقق» (Verification Code): 6 أحرف كبيرة على الملصق تحت رمز QR أسفل الكاميرا.\n" +
                "3. شغّل البث المحلي في تطبيق EZVIZ: صورة الحساب ← الإعدادات ← LAN Live View ← Start Scanning ← اختر الكاميرا ← ⚙ ← Local Server Settings ← فعّل RTSP.\n" +
                "4. أطفئ تشفير الفيديو: إعدادات الكاميرا ← الخصوصية ← Video Encryption.\n" +
                "ملاحظة: بعض موديلات EZVIZ الحديثة أوقفت فيها الشركة هذه الخدمة، فلا تعمل إلا بتطبيق EZVIZ.",
            "1. Address: press “Search network”, or in the EZVIZ app: camera → Settings → Device information.\n" +
                "2. User admin, and the password is the Verification Code: 6 capital letters on the label under the QR code, under the camera.\n" +
                "3. Turn on local streaming in the EZVIZ app: profile picture → Settings → LAN Live View → Start Scanning → pick the camera → ⚙ → Local Server Settings → enable RTSP.\n" +
                "4. Turn off video encryption: camera settings → Privacy → Video Encryption.\n" +
                "Note: on some new EZVIZ models the company turned this off, so they only work in the EZVIZ app.",
        )
        Brand.TAPO -> L.t(
            c,
            "1. العنوان: تطبيق Tapo ← الكاميرا ← ⚙ ← معلومات الجهاز (Device Info) ← عنوان IP.\n" +
                "2. أنشئ «حساب الكاميرا»: تطبيق Tapo ← الكاميرا ← ⚙ ← الإعدادات المتقدمة (Advanced Settings) ← حساب الكاميرا (Camera Account) ← اكتب اسم مستخدم وكلمة مرور (6 أحرف على الأقل).\n" +
                "3. اكتب هذا الاسم وكلمة المرور هنا، وليس بريد حساب تطبيق Tapo وكلمة مروره.\n" +
                "4. يجب أن تظهر الكاميرا «متصلة» في تطبيق Tapo، وأن يكون «وضع الخصوصية» مطفأً.\n" +
                "ملاحظة: كاميرات Tapo التي تعمل بالبطارية (مثل C400 وC420 وD230) لا تعمل بهذه الطريقة.",
            "1. Address: Tapo app → camera → ⚙ → Device Info → IP address.\n" +
                "2. Create a “Camera Account”: Tapo app → camera → ⚙ → Advanced Settings → Camera Account → type a user and a password (at least 6 characters).\n" +
                "3. Type that user and password here, not the email and password of your Tapo app account.\n" +
                "4. The camera must show “online” in the Tapo app, with Privacy Mode off.\n" +
                "Note: battery Tapo cameras (such as C400, C420 and D230) do not work this way.",
        )
        Brand.VIGI -> L.t(
            c,
            "1. العنوان: اضغط «بحث في الشبكة»، أو من تطبيق VIGI: الكاميرا ← الإعدادات ← معلومات الجهاز.\n" +
                "2. المستخدم admin، وكلمة المرور التي وضعتها عند تفعيل الكاميرا.\n" +
                "3. للتنبيهات والتحريك: فعّل ONVIF من صفحة الكاميرا: الإعدادات ← الشبكة ← متقدم ← ONVIF.",
            "1. Address: press “Search network”, or in the VIGI app: camera → Settings → Device information.\n" +
                "2. User admin, and the password you set when you activated the camera.\n" +
                "3. For alerts and PTZ: turn on ONVIF on the camera's page: Settings → Network → Advanced → ONVIF.",
        )
        Brand.REOLINK -> L.t(
            c,
            "1. العنوان: اضغط «بحث في الشبكة»، أو من تطبيق Reolink: الكاميرا ← ⚙ ← معلومات الشبكة.\n" +
                "2. المستخدم admin، وكلمة المرور التي وضعتها في تطبيق Reolink.\n" +
                "3. شغّل RTSP وONVIF: تطبيق Reolink ← ⚙ ← الشبكة ← متقدم ← إعدادات الخادم (Server Settings) ← فعّل RTSP وONVIF.\n" +
                "4. مسجّل Reolink: اكتب عنوان المسجّل ورقم القناة لكل كاميرا.\n" +
                "ملاحظة: كاميرات Reolink التي تعمل بالبطارية (مثل Argus) لا تعمل بهذه الطريقة.",
            "1. Address: press “Search network”, or in the Reolink app: camera → ⚙ → Network information.\n" +
                "2. User admin, and the password you set in the Reolink app.\n" +
                "3. Turn on RTSP and ONVIF: Reolink app → ⚙ → Network → Advanced → Server Settings → enable RTSP and ONVIF.\n" +
                "4. Reolink recorder: type the recorder's address and the channel of each camera.\n" +
                "Note: battery Reolink cameras (such as Argus) do not work this way.",
        )
        Brand.UNIVIEW -> L.t(
            c,
            "1. العنوان: اضغط «بحث في الشبكة»، أو من تطبيق EZView: الجهاز ← معلومات الجهاز.\n" +
                "2. المستخدم admin وكلمة مرور الجهاز.\n" +
                "3. مسجّل Uniview: اكتب عنوان المسجّل ورقم القناة لكل كاميرا.",
            "1. Address: press “Search network”, or in the EZView app: device → Device information.\n" +
                "2. User admin and the device password.\n" +
                "3. Uniview recorder: type the recorder's address and the channel of each camera.",
        )
        Brand.FOSCAM -> L.t(
            c,
            "1. العنوان: اضغط «بحث في الشبكة»، أو من تطبيق Foscam: الكاميرا ← الإعدادات ← معلومات الجهاز.\n" +
                "2. المستخدم وكلمة المرور اللذان وضعتهما للكاميرا في تطبيق Foscam (المستخدم عادةً admin).\n" +
                "3. المنفذ عادةً 88، وفي بعض الموديلات 554.",
            "1. Address: press “Search network”, or in the Foscam app: camera → Settings → Device information.\n" +
                "2. The user and password you set for the camera in the Foscam app (the user is usually admin).\n" +
                "3. The port is usually 88, on some models 554.",
        )
        Brand.EUFY -> L.t(
            c,
            "1. شغّل RTSP: تطبيق eufy Security ← الكاميرا ← ⚙ ← التخزين (Storage) ← NAS (RTSP) ← فعّله، واكتب اسم مستخدم وكلمة مرور.\n" +
                "2. يعرض التطبيق رابطًا مثل rtsp://192.168.1.30/live0: اكتب هنا العنوان الذي فيه (192.168.1.30)، ونفس الاسم وكلمة المرور.\n" +
                "ملاحظة: يعمل في بعض كاميرات eufy الداخلية الموصولة مباشرة بالواي فاي فقط، لا في كاميرات eufyCam التي تعمل مع HomeBase.",
            "1. Turn on RTSP: eufy Security app → camera → ⚙ → Storage → NAS (RTSP) → enable it, and type a user and a password.\n" +
                "2. The app shows a link like rtsp://192.168.1.30/live0: type its address here (192.168.1.30), with the same user and password.\n" +
                "Note: works only on some indoor eufy cameras joined straight to the Wi-Fi, not on eufyCam cameras with a HomeBase.",
        )
        Brand.WYZE -> L.t(
            c,
            "1. تحتاج الكاميرا (Wyze Cam v2 أو v3 أو Pan) نسخة برنامج خاصة من Wyze تدعم RTSP.\n" +
                "2. تطبيق Wyze ← الكاميرا ← ⚙ ← Advanced Settings ← RTSP ← فعّله، واكتب اسم مستخدم وكلمة مرور.\n" +
                "3. اكتب هنا العنوان الظاهر في الرابط الذي يعرضه التطبيق، ونفس الاسم وكلمة المرور.\n" +
                "ملاحظة: كثير من كاميرات Wyze الحديثة لا تدعم هذه الطريقة.",
            "1. The camera (Wyze Cam v2, v3 or Pan) needs a special Wyze firmware with RTSP.\n" +
                "2. Wyze app → camera → ⚙ → Advanced Settings → RTSP → enable it, and type a user and a password.\n" +
                "3. Type here the address shown in the link the app gives, with the same user and password.\n" +
                "Note: many new Wyze cameras do not support this.",
        )
        Brand.YOOSEE -> L.t(
            c,
            "1. العنوان: اضغط «بحث في الشبكة»، أو من تطبيق Yoosee: الكاميرا ← الإعدادات ← معلومات الشبكة.\n" +
                "2. ضع كلمة مرور للبث: تطبيق Yoosee ← الكاميرا ← الإعدادات ← الأمان (Security) ← كلمة مرور NVR أو RTSP.\n" +
                "3. المستخدم admin، وكلمة المرور هي التي وضعتها في الخطوة 2.",
            "1. Address: press “Search network”, or in the Yoosee app: camera → Settings → Network information.\n" +
                "2. Set a stream password: Yoosee app → camera → Settings → Security → NVR or RTSP password.\n" +
                "3. User admin, and the password you set in step 2.",
        )
        Brand.VSTARCAM -> L.t(
            c,
            "1. العنوان: اضغط «بحث في الشبكة»، أو من تطبيق Eye4: الكاميرا ← الإعدادات ← معلومات الجهاز.\n" +
                "2. المستخدم admin، وكلمة المرور هي كلمة مرور الكاميرا في تطبيق Eye4 (في الكاميرات القديمة 888888 ما لم تغيّرها).\n" +
                "3. المنفذ 10554.",
            "1. Address: press “Search network”, or in the Eye4 app: camera → Settings → Device information.\n" +
                "2. User admin, and the password is the camera's password in the Eye4 app (888888 on old cameras unless you changed it).\n" +
                "3. The port is 10554.",
        )
        Brand.IPWEBCAM -> L.t(
            c,
            "1. على الجوال القديم: ثبّت تطبيق IP Webcam من Google Play، ثم اضغط «Start server».\n" +
                "2. العنوان يظهر أسفل شاشته، والمنفذ 8080.\n" +
                "3. اترك المستخدم وكلمة المرور فارغين إذا لم تضعهما في IP Webcam.",
            "1. On the old phone: install IP Webcam from Google Play, then press “Start server”.\n" +
                "2. The address is shown at the bottom of its screen, port 8080.\n" +
                "3. Leave user and password empty unless you set them in IP Webcam.",
        )
        Brand.PHONE -> L.t(
            c,
            "1. على الجوال الآخر: افتح «كاميرات البيت» ← الإعدادات ← اجعل هذا الجوال كاميرا.\n" +
                "2. الأسهل: اضغط «بحث في الشبكة» هنا فيجده وحده.\n" +
                "3. المستخدم ipcam، وكلمة المرور الظاهرة على ذلك الجوال.",
            "1. On the other phone: open Home Cameras → Settings → Make this phone a camera.\n" +
                "2. Easiest: press “Search network” here and it finds it.\n" +
                "3. User ipcam, and the password shown on that phone.",
        )
        Brand.CUSTOM -> L.t(
            c,
            "1. لأي كاميرا أخرى: اكتب رابط RTSP كاملًا كما في دليل الكاميرا، وفيه اسم المستخدم وكلمة المرور كالمثال الظاهر في الخانة.\n" +
                "2. كثير من الكاميرات التي تدعم ONVIF يجدها «بحث في الشبكة» وحده.\n" +
                "لا تعمل مع هذا التطبيق (تعمل فقط بتطبيقاتها عبر الإنترنت): Xiaomi / Mi، وRing، وArlo، وGoogle Nest، وBlink، والكاميرات التي تعمل بالبطارية.",
            "1. For any other camera: type the full RTSP link from the camera's manual, with the user and password in it as in the example in the box.\n" +
                "2. Many cameras that support ONVIF are found by “Search network” on its own.\n" +
                "These do not work with this app (only with their own apps over the internet): Xiaomi / Mi, Ring, Arlo, Google Nest, Blink, and battery cameras.",
        )
    }
}
