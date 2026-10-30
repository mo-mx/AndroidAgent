Add-Type -AssemblyName System.Speech
$root = "C:\Users\Aqua\AndroidAgent\asr_eval"
New-Item -ItemType Directory -Force -Path "$root\wav" | Out-Null

# 30 条中文测试指令：简短 / 联系人 / 复杂 / 口语与停顿
$cmds = @(
  @{id="01"; text="打开相机"},
  @{id="02"; text="查询电量"},
  @{id="03"; text="打开手电筒"},
  @{id="04"; text="现在几点"},
  @{id="05"; text="打开设置"},
  @{id="06"; text="关闭手电筒"},
  @{id="07"; text="打开蓝牙"},
  @{id="08"; text="查询剩余电量"},
  @{id="09"; text="给妈妈打电话"},
  @{id="10"; text="给爸爸打电话"},
  @{id="11"; text="联系紧急联系人"},
  @{id="12"; text="给张三发短信"},
  @{id="13"; text="拨打最近的通话记录"},
  @{id="14"; text="给李四打电话"},
  @{id="15"; text="打开相机录制五秒视频"},
  @{id="16"; text="查询当前位置并播报"},
  @{id="17"; text="帮我查一下今天的天气"},
  @{id="18"; text="打开浏览器搜索三星折叠屏手机"},
  @{id="19"; text="导航回家"},
  @{id="20"; text="定一个十分钟的闹钟"},
  @{id="21"; text="提醒我半小时后吃药"},
  @{id="22"; text="把屏幕亮度调到一半"},
  @{id="23"; text="嗯那个帮我看看还剩多少电"},
  @{id="24"; text="我看看啊，现在几点了"},
  @{id="25"; text="那个，打开一下相机"},
  @{id="26"; text="帮我看下电量呗"},
  @{id="27"; text="电量还剩多少来着"},
  @{id="28"; text="麻烦你打开手电筒"},
  @{id="29"; text="打开相机，录制一段五秒的视频"},
  @{id="30"; text="查询当前的定位并告诉我"}
)

$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.SelectVoice("Microsoft Huihui Desktop")
$refs = @()
foreach ($c in $cmds) {
  $path = "$root\wav\$($c.id).wav"
  $synth.SetOutputToWaveFile($path, $fmt)
  $synth.Speak($c.text)
  $refs += [pscustomobject]@{ id=$c.id; text=$c.text; wav=$path }
}
$synth.SetOutputToNull()
$synth.Dispose()
$refs | ConvertTo-Json -Depth 3 | Out-File -Encoding utf8 "$root\refs.json"
Write-Output ("generated " + $refs.Count + " files")
