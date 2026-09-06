from pathlib import Path
# Idempotent compatibility preparation: String.join is API 26 on Android.
base=Path(__file__).parent/'app/src/main/java/ru/ratunov/bukvar/nativeapp'
for name in ('MainActivity.java','ReadingRules.java'):
 p=base/name;s=p.read_text()
 if name=='ReadingRules.java' and 'public static String join(' not in s:
  s=s.replace('private ReadingRules() {}','private ReadingRules() {}\n public static String join(String separator,String[] items){StringBuilder b=new StringBuilder();for(String item:items){if(b.length()>0)b.append(separator);b.append(item);}return b.toString();}')
 s=s.replace('String.join(', 'ReadingRules.join(')
 if name=='MainActivity.java':
  s=s.replace('private String statusText(){String s=', 'private String statusText(){if(!customText.isEmpty())return "Своя строка. Отметки занятий не меняются.";String s=')
 p.write_text(s)
