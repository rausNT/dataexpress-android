from pathlib import Path
# Java's String.join is API 26 on Android. Use a local helper on our API 23 baseline.
base=Path(__file__).parent/'app/src/main/java/ru/ratunov/bukvar/nativeapp'
for name in ('MainActivity.java','ReadingRules.java'):
 p=base/name
 s=p.read_text()
 if name=='ReadingRules.java':
  s=s.replace('private ReadingRules() {}','private ReadingRules() {}\n public static String join(String separator,String[] items){StringBuilder b=new StringBuilder();for(String item:items){if(b.length()>0)b.append(separator);b.append(item);}return b.toString();}')
 s=s.replace('String.join(', 'ReadingRules.join(')
 p.write_text(s)
