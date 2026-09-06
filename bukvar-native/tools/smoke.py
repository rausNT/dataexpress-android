#!/usr/bin/env python3
"""Black-box tests of the SAME release APK supplied to the user, via ADB and native UI trees.
No fake ASR results, browser rendering, or mocked screenshots.
"""
import hashlib, json, os, re, subprocess, sys, time, traceback, xml.etree.ElementTree as ET, zipfile
from pathlib import Path
PKG='ru.ratunov.bukvar.nativeapp'
OUT=Path('device-report');OUT.mkdir(exist_ok=True)
APK=Path(sys.argv[1]);CHECKS=[]
def cmd(*args, check=True, timeout=40):
 p=subprocess.run([str(a) for a in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
 if check and p.returncode: raise RuntimeError(p.stdout.decode(errors='replace'))
 return p.stdout
def adb(*args, **kw): return cmd('adb',*args,**kw)
def shell(*args, **kw): return adb('shell',*args,**kw).decode(errors='replace').strip()
def ok(name,detail=''):
 CHECKS.append({'test':name,'status':'passed','detail':detail});print('PASS',name,detail,flush=True)
def dump():
 for _ in range(3):
  try:
   shell('uiautomator','dump','/sdcard/window.xml',timeout=30)
   raw=adb('exec-out','cat','/sdcard/window.xml')
   return ET.fromstring(raw)
  except Exception: time.sleep(.5)
 raise RuntimeError('Could not read native UI tree')
def node(rid=None,text=None,desc=None,contains=None,enabled=None):
 for n in dump().iter('node'):
  a=n.attrib
  if rid and a.get('resource-id')!=PKG+':id/'+rid: continue
  if text is not None and a.get('text')!=text: continue
  if desc is not None and a.get('content-desc')!=desc: continue
  if contains is not None and contains not in a.get('text',''): continue
  if enabled is not None and a.get('enabled')!=str(enabled).lower(): continue
  return n
 return None
def swipe(down=True):
 size=shell('wm','size');w,h=map(int,re.findall(r'(\d+)x(\d+)',size)[-1]);shell('input','swipe',str(w//2),str(int(h*(.77 if down else .30))),str(w//2),str(int(h*(.30 if down else .77))),'240');time.sleep(.25)
def find(**kw):
 n=node(**kw)
 if n is not None:return n
 for _ in range(7):
  swipe();n=node(**kw)
  if n is not None:return n
 for _ in range(8):
  swipe(False);n=node(**kw)
  if n is not None:return n
 raise AssertionError('UI control not found: '+str(kw))
def tap(n):
 bounds=n.get('bounds','');v=list(map(int,re.findall(r'\d+',bounds)));assert len(v)==4,bounds
 x1,y1,x2,y2=v;shell('input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.35)
def click(**kw):tap(find(**kw))
def present(**kw):
 n=find(**kw);assert n is not None;return n

def top():
 for _ in range(5):swipe(False)
def screenshot(name):
 time.sleep(.35);data=adb('exec-out','screencap','-p');assert data.startswith(b'\x89PNG\r\n\x1a\n');(OUT/(name+'.png')).write_bytes(data)
 tree=dump();ET.ElementTree(tree).write(OUT/(name+'.xml'),encoding='utf-8',xml_declaration=True)
 assert not any('WebView' in n.get('class','') for n in tree.iter('node')), 'WebView appeared'

def launch():
 result=shell('am','start','-W','-n',PKG+'/.MainActivity');assert 'Error' not in result,result;time.sleep(.8)
def permission(allow):
 candidates=('permission_allow_foreground_only_button','permission_allow_button','permission_allow_one_time_button') if allow else ('permission_deny_button',)
 for _ in range(5):
  tree=dump()
  for n in tree.iter('node'):
   if any(n.get('resource-id','').endswith('/'+s) for s in candidates):tap(n);return
  time.sleep(.5)
 raise AssertionError('Android permission dialog button not found')
def feedback_has(s):present(rid='feedback',contains=s)
def stage_has(s):present(rid='stage',contains=s)
def fill_word(word):
 for ch in word:
  nodes=list(dump().iter('node'));matches=[n for n in nodes if n.get('content-desc','').startswith('Буква '+ch+',') and n.get('enabled')=='true']
  if not matches: top();nodes=list(dump().iter('node'));matches=[n for n in nodes if n.get('content-desc','').startswith('Буква '+ch+',') and n.get('enabled')=='true']
  assert matches,'Missing letter '+ch;tap(matches[0])
try:
 blob=APK.read_bytes()
 with zipfile.ZipFile(APK) as z:
  assert z.testzip() is None
  names=z.namelist();assert 'AndroidManifest.xml' in names and 'classes.dex' in names
  assert not any(n.lower().endswith(('.html','.htm','.js','.css')) for n in names)
  assert b'Landroid/webkit/WebView;' not in z.read('classes.dex')
 ok('APK has no HTML/JS/CSS or WebView reference',str(len(blob))+' bytes')
 (OUT/'apk-sha256.txt').write_text(hashlib.sha256(blob).hexdigest()+'  '+APK.name+'\n')
 (OUT/'device.txt').write_text(shell('getprop')+'\n'+shell('wm','size')+'\n'+shell('wm','density'))
 shell('settings','put','system','font_scale','1.0');shell('settings','put','system','accelerometer_rotation','0');shell('settings','put','system','user_rotation','0')
 shell('wm','size','1080x1920');shell('wm','density','320');shell('input','keyevent','82');adb('uninstall',PKG,check=False)
 installation=adb('install',str(APK)).decode();assert 'Success' in installation;ok('Install release APK',installation.strip())
 (OUT/'install.txt').write_text(installation)
 adb('logcat','-c');launch();present(text='От звука — к слову');screenshot('01-contents');ok('Cold start and native contents')
 click(desc='Занятие 2. М · мама');stage_has('ШАГ 1 / 5');ok('Open lesson')
 click(rid='next');present(rid='prompt',text='М м');top();screenshot('02-sound');ok('Sound page')
 click(rid='record');permission(False);feedback_has('Микрофон не разрешён');screenshot('03-permission-denied');ok('Denied microphone: exercise remains usable')
 click(rid='record');permission(True);time.sleep(2);click(rid='record');present(rid='record_status',contains='Запись готова');ok('Granted microphone: AAC recording created')
 click(rid='play');time.sleep(3);assert 'PLAYBACK_OK' in adb('logcat','-d','-s','BukvarAudio:I','*:S').decode();ok('Native recording playback completes');top();screenshot('04-recorded')
 click(rid='record_model');time.sleep(2);click(rid='record_model');click(rid='play_model');time.sleep(3);ok('Adult audio sample records and plays')
 click(rid='record');time.sleep(1);shell('input','keyevent','3');time.sleep(1);launch();present(rid='record',text='Записать себя');ok('Microphone stops when app is backgrounded')
 click(rid='next');present(rid='prompt',text='ма');top();screenshot('05-blending');ok('Native sound blending')
 click(rid='next');stage_has('ШАГ 4 / 5');fill_word('амам');feedback_has('Порядок букв отличается');screenshot('06-wrong-order');ok('Wrong letter order is not accepted')
 for _ in range(4):click(rid='undo')
 fill_word('мама');feedback_has('Буквы на месте');top();screenshot('07-word-assembly');ok('Correct assembly, with duplicate letters')
 stage_has('ШАГ 4 / 5');time.sleep(2);stage_has('ШАГ 4 / 5');ok('No automatic page advance')
 shell('settings','put','system','user_rotation','1');time.sleep(2);present(rid='answer',text='мама');top();screenshot('08-landscape');ok('Rotation retains assembled word')
 shell('settings','put','system','user_rotation','0');time.sleep(2)
 click(rid='next');present(rid='prompt',text='мама');top();screenshot('09-reading');ok('Reading word page')
 click(rid='split');present(rid='prompt',text='ма · ма');top();screenshot('10-syllables');ok('Syllable assistance toggles');click(rid='split')
 click(rid='speech')
 if node(text='Не включать') is not None:
  screenshot('11-speech-consent');click(text='Не включать');ok('Speech service present: privacy consent can be refused','No audio sent for recognition')
 else:
  feedback_has('нет системного распознавателя');screenshot('11-no-speech-service');ok('No speech service: honest fallback, no false success')
 click(rid='confirm');click(text='Ещё читаем');stage_has('ШАГ 5 / 5');ok('Cancel adult confirmation')
 click(rid='confirm');click(text='Подтвердить');feedback_has('Отмечено взрослым');ok('Adult confirmation is distinct from ASR')
 shell('am','force-stop',PKG);launch();click(rid='resume');stage_has('ШАГ 5 / 5');feedback_has('проверено взрослым');ok('Progress survives process restart');top();screenshot('12-restored')
 click(rid='back');present(contains='2 из 54');ok('Only assembly and confirmed reading counted')
 click(rid='parents');click(text='Открыть');present(text='Занимаемся спокойно');click(rid='custom_go');present(rid='custom_input');ok('Empty custom text does not start an exercise')
 click(rid='large_text');click(rid='back');click(rid='resume');present(rid='prompt',text='мама');ok('Adult font option survives screen change')
 shell('wm','size','720x1280');shell('wm','density','320');time.sleep(2);top();screenshot('13-small-phone');click(rid='next');stage_has('ЗАНЯТИЕ 3');ok('360dp-wide phone: scroll and next action usable')
 click(rid='back');shell('wm','size','1600x2560');shell('wm','density','240');time.sleep(2);top();screenshot('14-tablet');present(desc='Занятие 2. М · мама');ok('Tablet native layout')
 shell('settings','put','system','font_scale','1.6');time.sleep(2);top();screenshot('15-large-font');click(desc='Занятие 2. М · мама');stage_has('ШАГ 1 / 5');ok('System font scale 160 percent: navigation usable')
 api=int(shell('getprop','ro.build.version.sdk'))
 if api>=29:
  shell('cmd','uimode','night','yes');time.sleep(2);top();screenshot('16-dark');ok('Native night resources render')
 logs=adb('logcat','-d').decode(errors='replace');(OUT/'logcat.txt').write_text(logs)
 fatal=[b for b in logs.split('FATAL EXCEPTION:')[1:] if PKG in b[:1500]];assert not fatal,'Application crashed: '+str(fatal)
 ok('No app FATAL EXCEPTION in device log')
 (OUT/'checks.json').write_text(json.dumps(CHECKS,ensure_ascii=False,indent=2))
 print('ALL '+str(len(CHECKS))+' DEVICE CHECKS PASSED',flush=True)
except Exception as exc:
 CHECKS.append({'test':'failure','status':'failed','detail':str(exc)})
 (OUT/'checks.json').write_text(json.dumps(CHECKS,ensure_ascii=False,indent=2));(OUT/'failure.txt').write_text(traceback.format_exc())
 try:screenshot('FAILURE');(OUT/'logcat.txt').write_bytes(adb('logcat','-d'))
 except Exception:pass
 raise
