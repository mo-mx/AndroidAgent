from pathlib import Path
import shutil,ast,json
R=Path(__file__).parent; H=R/'harness'; H.mkdir(exist_ok=True)
base=R.parent/'src/closepaw'
for f in ['gradlew.bat','gradlew']:
 shutil.copyfile(base/f,H/f)
shutil.copytree(base/'gradle/wrapper',H/'gradle/wrapper',dirs_exist_ok=True)
(H/'settings.gradle.kts').write_text('pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name="MobileActionsDryRun"\ninclude(":app")')
(H/'build.gradle.kts').write_text('plugins { id("com.android.application") version "8.9.1" apply false; id("org.jetbrains.kotlin.android") version "2.3.0" apply false }')
(H/'local.properties').write_text('sdk.dir=C:/Users/Aqua/AndroidAgent/tools/android-sdk')
(H/'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx3g\nsystemProp.https.proxyHost=127.0.0.1\nsystemProp.https.proxyPort=7897\nsystemProp.http.proxyHost=127.0.0.1\nsystemProp.http.proxyPort=7897\n')
app=H/'app'; src=app/'src/main'; kt=src/'java/eval/mobileactions'; kt.mkdir(parents=True,exist_ok=True)
(app/'build.gradle.kts').write_text('''plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace="eval.mobileactions"; compileSdk=36
 defaultConfig { applicationId="eval.mobileactions"; minSdk=31; targetSdk=36; versionCode=1; versionName="1.0"; ndk { abiFilters += "arm64-v8a" } }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
}
dependencies { implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") }
''')
(src/'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="Mobile Actions Dry Run" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name=".MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>''')
official=R/'gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions'
for name in ['Actions.kt','MobileActionsTools.kt']: shutil.copyfile(official/name,kt/name)
# Existing 15 cases, same text. Add native supported-tool controls and negative control.
tree=ast.parse((R.parent/'llm_eval/bench.py').read_text(encoding='utf-8'))
cases=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='cases' for t in n.targets))
texts=[c[0] for c in cases]+['Turn on the flashlight','打开手电筒','Turn off the flashlight','关闭手电筒','Open Wi-Fi settings','打开Wi-Fi设置','Show Beijing on the map','在地图上显示北京']
assets=src/'assets'; assets.mkdir(exist_ok=True);(assets/'cases.json').write_text(json.dumps(texts,ensure_ascii=False),encoding='utf-8')
(kt/'MainActivity.kt').write_text('''package eval.mobileactions
import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.widget.TextView
import android.util.Log
import com.google.ai.edge.litertlm.*
import com.google.ai.edge.gallery.customtasks.mobileactions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
class MainActivity: Activity() {
 override fun onCreate(b:Bundle?) { super.onCreate(b); window.addFlags(128);setContentView(TextView(this).apply {text="Official Mobile Actions dry-run: no Android tools execute"})
 Thread { runBlocking { runEval() } }.start()
 }
 suspend fun runEval() {
 val out=File(getExternalFilesDir(null),"results.jsonl"); out.writeText("")
 fun save(j:JSONObject) {out.appendText(j.toString()+"\\n");Log.i("MADryRun",j.toString())}
 try {
 val start=System.nanoTime()
 val engine=Engine(EngineConfig(modelPath=File(getExternalFilesDir(null),"mobile_actions.litertlm").absolutePath,backend=Backend.CPU(),maxNumTokens=1024))
 engine.initialize();save(JSONObject().put("event","loaded").put("load_ms",(System.nanoTime()-start)/1e6))
 val prompts=JSONArray(assets.open("cases.json").bufferedReader().readText())
 for(i in 0 until prompts.length()) {
 val input=prompts.getString(i); val calls=JSONArray(); val now=LocalDateTime.now()
 val tools=listOf(tool(MobileActionsTools { action -> calls.put(JSONObject().put("type",action.javaClass.simpleName).put("detail",action.toString())) }))
 val sys=Contents.of(listOf(Content.Text("You are a model that can do function calling with the following functions"),Content.Text("Current date and time given in YYYY-MM-DDTHH:MM:SS format: "+now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))+"\\nDay of week is "+now.format(DateTimeFormatter.ofPattern("EEEE")))))
 val c=engine.createConversation(ConversationConfig(samplerConfig=SamplerConfig(topK=64,topP=0.95,temperature=0.0),systemInstruction=sys,tools=tools))
 val t=System.nanoTime();val raw=StringBuilder()
 try { withTimeout(45000) { c.sendMessageAsync(Contents.of(listOf(Content.Text(input)))).collect { raw.append(it.toString()) } }
 val mem=Debug.MemoryInfo();Debug.getMemoryInfo(mem)
 save(JSONObject().put("case",i+1).put("input",input).put("calls",calls).put("raw",raw.toString()).put("elapsed_ms",(System.nanoTime()-t)/1e6).put("pss_KiB",mem.totalPss))
 }catch(e:Throwable){save(JSONObject().put("case",i+1).put("input",input).put("error",e.toString()).put("calls",calls));runCatching {c.cancelProcess()} }
 runCatching {c.close()}
 }
 engine.close();save(JSONObject().put("event","done"))
 }catch(e:Throwable){save(JSONObject().put("event","fatal").put("error",Log.getStackTraceString(e)))}
 }
}
''',encoding='utf-8')
print(H)
