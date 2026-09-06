from pathlib import Path
# Idempotent preparation; delivered sources already contain these adjustments.
root=Path(__file__).parent
base=root/'app/src/main/java/ru/ratunov/bukvar/nativeapp'
for name in ('MainActivity.java','ReadingRules.java'):
 p=base/name;s=p.read_text()
 if name=='ReadingRules.java' and 'public static String join(' not in s:
  s=s.replace('private ReadingRules() {}','private ReadingRules() {}\n public static String join(String separator,String[] items){StringBuilder b=new StringBuilder();for(String item:items){if(b.length()>0)b.append(separator);b.append(item);}return b.toString();}')
 s=s.replace('String.join(', 'ReadingRules.join(')
 if name=='MainActivity.java':
  s=s.replace('private String statusText(){String s=', 'private String statusText(){if(!customText.isEmpty())return "Своя строка. Отметки занятий не меняются.";String s=')
  if 'onWindowFocusChanged(boolean hasFocus)' not in s:
   s=s.replace(' private int c(int id)', ''' @Override public void onWindowFocusChanged(boolean hasFocus){
  super.onWindowFocusChanged(hasFocus);
  if(hasFocus&&Build.VERSION.SDK_INT>=30&&getWindow().getInsetsController()!=null){
   boolean night=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
   int mask=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
   getWindow().getInsetsController().setSystemBarsAppearance(night?0:mask,mask);
  }
 }
 private int c(int id)''')
 p.write_text(s)
# Close only an unrelated Pixel Launcher ANR, never an ANR belonging to this app.
p=root/'tools/smoke.py';s=p.read_text()
old="   return ET.fromstring(adb('exec-out','cat','/sdcard/window.xml'))"
new='''   tree=ET.fromstring(adb('exec-out','cat','/sdcard/window.xml'))
   if any(n.get('text', '') == "Pixel Launcher isn't responding" for n in tree.iter('node')):
    (OUT/'emulator-launcher-anr.png').write_bytes(adb('exec-out','screencap','-p'))
    (OUT/'emulator-launcher-anr.xml').write_bytes(ET.tostring(tree,encoding='utf-8'))
    for n in tree.iter('node'):
     if n.get('resource-id')=='android:id/aerr_close':
      x1,y1,x2,y2=map(int,re.findall(r'\\d+',n.get('bounds','')))
      shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
      print('ENVIRONMENT: dismissed Pixel Launcher ANR, screenshot preserved',flush=True)
      break
    time.sleep(2)
    continue
   return tree'''
s=s.replace(old,new)
s=s.replace("adb('logcat','-c');launch();", "time.sleep(8);adb('logcat','-c');launch();") if 'time.sleep(8);' not in s else s
p.write_text(s)
