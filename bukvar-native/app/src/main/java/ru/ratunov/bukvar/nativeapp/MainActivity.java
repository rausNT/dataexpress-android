package ru.ratunov.bukvar.nativeapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.File;
import java.util.*;

/** All screens are native Views. There is no browser, JavaScript, or network client. */
public final class MainActivity extends Activity {
 private static final int REQUEST_AUDIO = 71;
 private SharedPreferences prefs;
 private LinearLayout body;
 private ScrollView scroll;
 private int lesson, task, screen; // 0 contents, 1 exercise, 2 adults
 private boolean assisted, split, hintVisible, large, vowels;
 private String customText = "", selection = "", lastFeedback = "";
 private final List<Integer> picked = new ArrayList<>();
 private final Handler handler = new Handler(Looper.getMainLooper());
 private MediaRecorder recorder;
 private MediaPlayer player;
 private SpeechRecognizer recognizer;
 private TextToSpeech tts;
 private boolean ttsReady, recognizing, recordingModel, cloudConsent;
 private int epoch, pendingAudio;
 private long recordStarted;
 private File childClip, adultClip;
 private Button recordButton, playButton, modelButton, playModelButton, speechButton;
 private TextView promptView, feedbackView, answerView, recordStatus;
 private LinearLayout bankView;
 private Runnable recordTimeout, speechTimeout;

 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  prefs=getSharedPreferences("bukvar_native_v1", MODE_PRIVATE);
  lesson=Math.max(0,Math.min(Course.LESSONS.size()-1,prefs.getInt("lesson",0)));
  task=Math.max(0,Math.min(Course.LESSONS.get(lesson).tasks.size()-1,prefs.getInt("task",0)));
  large=prefs.getBoolean("large",false);vowels=prefs.getBoolean("vowels",true);
  if(state!=null){screen=state.getInt("screen",0);lesson=state.getInt("lesson",lesson);task=state.getInt("task",task);customText=state.getString("custom","");selection=state.getString("selection","");assisted=state.getBoolean("assisted");split=state.getBoolean("split");hintVisible=state.getBoolean("hint");lastFeedback=state.getString("feedback","");int[] a=state.getIntArray("picked");if(a!=null)for(int i:a)picked.add(i);}
  setContentView(R.layout.activity_main);
  body=findViewById(R.id.body);scroll=findViewById(R.id.scroll);
  setupInsets();
  findViewById(R.id.back).setOnClickListener(v->goHome());
  findViewById(R.id.parents).setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Раздел для взрослого").setMessage("Настройки, своё слово и сведения о проверке чтения.").setNegativeButton("Отмена",null).setPositiveButton("Открыть",(d,w)->{leaveExercise();screen=2;render();}).show());
  render();
 }
 private int c(int id){return getColor(id);}
 private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
 private void setupInsets(){
  View root=findViewById(R.id.root);
  boolean night=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
  if(Build.VERSION.SDK_INT>=30){
   getWindow().setDecorFitsSystemWindows(false);
   getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
   root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
   if(getWindow().getInsetsController()!=null)getWindow().getInsetsController().setSystemBarsAppearance(night?0:android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
  }else{
   getWindow().setStatusBarColor(c(R.color.paper));getWindow().setNavigationBarColor(c(R.color.paper));
   int flags=night?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
   if(Build.VERSION.SDK_INT>=26&&!night)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
   getWindow().getDecorView().setSystemUiVisibility(flags);
  }
  root.requestApplyInsets();
 }
 private GradientDrawable bg(int fill,int border,int radius){GradientDrawable g=new GradientDrawable();g.setColor(c(fill));g.setCornerRadius(dp(radius));if(border!=0)g.setStroke(dp(1),c(border));return g;}
 private TextView text(String s,float size,int color,boolean serif){TextView t=new TextView(this);t.setText(s);t.setTextColor(c(color));t.setTextSize(size);t.setFontFeatureSettings("kern");t.setLineSpacing(dp(2),1.05f);if(serif)t.setTypeface(Typeface.SERIF);t.setLayoutParams(lp(-1,-2));return t;}
 private void gap(int n){View v=new View(this);body.addView(v,lp(1,dp(n)));}
 private void label(String s){TextView t=text(s,12,R.color.red,false);t.setLetterSpacing(.06f);body.addView(t);gap(8);}
 private Button button(String title,int id,boolean filled,View.OnClickListener action){
  Button b=new Button(this);if(id!=0)b.setId(id);b.setText(title);b.setAllCaps(false);b.setTextSize(17);b.setTextColor(c(filled?R.color.on_green:R.color.green));b.setPadding(dp(14),dp(9),dp(14),dp(9));b.setMinHeight(dp(52));b.setMinimumHeight(dp(52));b.setStateListAnimator(null);b.setBackgroundTintList(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(c(R.color.line)),bg(filled?R.color.green:R.color.sheet,filled?0:R.color.line,10),null));b.setOnClickListener(action);b.setLayoutParams(lp(-1,-2));return b;
 }
 private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setOrientation(LinearLayout.HORIZONTAL);r.setLayoutParams(lp(-1,-2));return r;}
 private void inRow(LinearLayout row,View item,boolean end){LinearLayout.LayoutParams p=lp(0,-2);p.weight=1;if(!end)p.setMarginEnd(dp(8));row.addView(item,p);}
 private Course.Task current(){return customText.isEmpty()?Course.LESSONS.get(lesson).tasks.get(task):Course.read(customText,null,"Обсудите смысл прочитанной строки.");}
 private String key(){return "done_"+lesson+"_"+task;}
 private void persist(){prefs.edit().putInt("lesson",lesson).putInt("task",task).putBoolean("started",true).putBoolean("large",large).putBoolean("vowels",vowels).apply();}
 private int completed(){int n=0;for(String k:prefs.getAll().keySet())if(k.startsWith("done_"))n++;return n;}
 private void render(){
  body.removeAllViews();feedbackView=null;promptView=null;recordButton=null;playButton=null;modelButton=null;playModelButton=null;speechButton=null;recordStatus=null;
  int width=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
  int margin=width>780?(width-740)/2:20;body.setPadding(dp(margin),dp(20),dp(margin),dp(28));
  ((TextView)findViewById(R.id.top_subtitle)).setText(screen==2?"РАЗДЕЛ ДЛЯ ВЗРОСЛОГО":"ЧИТАЕМ ВМЕСТЕ");
  if(screen==0)home();else if(screen==1)exercise();else parents();
  scroll.post(()->scroll.scrollTo(0,0));
 }
 private void home(){
  label("ПЕРВЫЕ ШАГИ В ЧТЕНИИ");body.addView(text("От звука — к слову",32,R.color.green,true));gap(10);
  body.addView(text("Слушаем. Соединяем звуки.\nСкладываем и читаем сами.",17,R.color.muted,false));gap(18);
  if(prefs.getBoolean("started",false)){body.addView(button("Продолжить: "+Course.LESSONS.get(lesson).title,R.id.resume,true,v->{screen=1;customText="";render();}));gap(14);}
  body.addView(text("9 вводных занятий · "+completed()+" из "+Course.size()+" заданий отмечено",14,R.color.muted,false));gap(14);
  LinearLayout grid=new LinearLayout(this);grid.setId(R.id.lesson_grid);grid.setOrientation(LinearLayout.VERTICAL);body.addView(grid);
  boolean two=getResources().getConfiguration().screenWidthDp>=600;LinearLayout line=null;
  for(int i=0;i<Course.LESSONS.size();i++){
   final int index=i;Course.Lesson l=Course.LESSONS.get(i);
   if(!two||i%2==0){line=row();LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(10);grid.addView(line,p);}
   Button b=button(String.format(Locale.ROOT,"%02d   %s\n%s",i+1,l.title,l.subtitle),0,false,v->openLesson(index,0));b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setTextSize(17);b.setMinHeight(dp(84));b.setContentDescription("Занятие "+(i+1)+". "+l.title);inRow(line,b,!two||i%2==1);
  }
  gap(10);body.addView(text("Без таймера и оценок. Переход к следующей странице — только по нажатию.",14,R.color.muted,false));
 }
 private void openLesson(int l,int t){leaveExercise();lesson=l;task=t;customText="";screen=1;resetExercise();persist();render();}
 private void resetExercise(){selection="";picked.clear();assisted=false;split=false;hintVisible=false;lastFeedback="";}
 private void goHome(){leaveExercise();screen=0;customText="";resetExercise();render();}
 private void exercise(){
  Course.Task t=current();boolean custom=!customText.isEmpty();
  TextView stage=text(custom?"СВОЯ СТРОКА":"ЗАНЯТИЕ "+(lesson+1)+" / 9   ·   ШАГ "+(task+1)+" / "+Course.LESSONS.get(lesson).tasks.size(),12,R.color.red,false);stage.setId(R.id.stage);stage.setLetterSpacing(.04f);body.addView(stage);gap(10);
  String title=switch(t.kind){case "talk"->"Поговорим и послушаем";case "sound"->"Послушай. Произнеси.";case "blend"->"Соедини звуки";case "build"->"Сложи из букв";default->t.text.contains(" ")?"Прочитай строку":"Прочитай сам";};
  body.addView(text(title,28,R.color.green,true));gap(5);
  body.addView(text(t.kind.equals("talk")?"Устное задание. Взрослый читает вопрос.":t.kind.equals("build")?"Взрослый произносит слово: «"+t.text+"».":t.kind.equals("sound")?"Произносим звук, а не название буквы.":"Не спеши. Читай слева направо.",15,R.color.muted,false));gap(16);
  if(t.kind.equals("build"))buildBoard(t);else{
   promptView=text("",t.kind.equals("talk")?29:t.text.length()>11?36:t.text.length()>6?48:68,R.color.ink,true);promptView.setId(R.id.prompt);promptView.setGravity(Gravity.CENTER);promptView.setPadding(dp(12),dp(20),dp(12),dp(20));promptView.setMinHeight(dp(t.kind.equals("talk")?155:164));promptView.setBackground(bg(R.color.sheet,R.color.line,12));body.addView(promptView);paintPrompt(-1);
   if(!t.kind.equals("talk")){gap(4);SeekBar guide=new SeekBar(this);guide.setId(R.id.reader_guide);guide.setMax(Math.max(0,t.text.length()-1));guide.setContentDescription("Веди пальцем по строке");guide.setProgressTintList(ColorStateList.valueOf(c(R.color.green)));guide.setThumbTintList(ColorStateList.valueOf(c(R.color.green)));body.addView(guide,lp(-1,dp(48)));guide.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int n,boolean user){if(user)paintPrompt(n);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){paintPrompt(-1);}});}
  }
  gap(12);
  LinearLayout tools=row();inRow(tools,button(hintVisible?"Скрыть помощь":"Помощь взрослому",R.id.hint,false,v->{assisted=true;hintVisible=!hintVisible;TextView h=findViewById(10001);h.setVisibility(hintVisible?View.VISIBLE:View.GONE);((Button)v).setText(hintVisible?"Скрыть помощь":"Помощь взрослому");}),t.syllables==null);
  if(t.syllables!=null)inRow(tools,button(split?"Без слогов":"По слогам",R.id.split,false,v->{split=!split;assisted=true;((Button)v).setText(split?"Без слогов":"По слогам");paintPrompt(-1);}),true);body.addView(tools);
  TextView help=text(t.hint,15,R.color.muted,false);help.setId(10001);help.setPadding(dp(10),dp(12),dp(10),dp(12));help.setVisibility(hintVisible?View.VISIBLE:View.GONE);body.addView(help);
  if(!t.kind.equals("talk"))audioControls(t);
  gap(12);feedbackView=text(lastFeedback.isEmpty()?statusText():lastFeedback,15,R.color.muted,false);feedbackView.setId(R.id.feedback);feedbackView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);feedbackView.setPadding(dp(12),dp(12),dp(12),dp(12));feedbackView.setBackground(bg(R.color.pale,0,8));body.addView(feedbackView);
  gap(12);body.addView(button("Взрослый: прочитано",R.id.confirm,false,v->new AlertDialog.Builder(this).setTitle("Подтверждение взрослого").setMessage("Ребёнок самостоятельно выполнил задание? Это ручная отметка, не результат распознавания.").setNegativeButton("Ещё читаем",null).setPositiveButton("Подтвердить",(d,w)->{mark("adult");feedback("Отмечено взрослым"+(assisted?" · с подсказкой":"")+". Можно переходить дальше.");}).show()));
  if(t.kind.equals("read")&&!t.meaning.isEmpty()){gap(12);body.addView(text("После чтения: "+t.meaning,15,R.color.muted,false));}
  gap(20);LinearLayout nav=row();Button previous=button("Назад",R.id.prev,false,v->advance(-1));previous.setEnabled(!custom&&(lesson>0||task>0));inRow(nav,previous,false);inRow(nav,button(custom?"К занятиям":"Дальше",R.id.next,true,v->{if(custom)goHome();else advance(1);}),true);body.addView(nav);
 }
 private String statusText(){String s=prefs.getString(key(),"");return s.isEmpty()?"Отметки пока нет. Переход дальше сам по себе не означает правильный ответ.":"Сохранённая отметка: "+(s.startsWith("adult")?"проверено взрослым":s.startsWith("assembly")?"буквы собраны по порядку":"совпал текст")+(s.contains("help")?" · с подсказкой":"");}
 private void paintPrompt(int index){if(promptView==null)return;Course.Task t=current();String s=t.kind.equals("sound")?t.text.toUpperCase(Locale.forLanguageTag("ru"))+" "+t.text:split&&t.syllables!=null?String.join(" · ",t.syllables):t.text;SpannableString span=new SpannableString(s);if(vowels&&!t.kind.equals("talk"))for(int i=0;i<s.length();i++)if("аеёиоуыэюя".indexOf(Character.toLowerCase(s.charAt(i)))>=0)span.setSpan(new ForegroundColorSpan(c(R.color.red)),i,i+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);if(index>=0&&index<s.length())span.setSpan(new BackgroundColorSpan(c(R.color.highlight)),index,index+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);promptView.setText(span);if(large&&!t.kind.equals("talk"))promptView.setTextSize(t.text.length()>11?43:t.text.length()>6?55:80);}
 private void buildBoard(Course.Task t){
  answerView=text(selection.isEmpty()?"·  ·  ·":selection,54,R.color.ink,true);answerView.setId(R.id.answer);answerView.setGravity(Gravity.CENTER);answerView.setMinHeight(dp(108));answerView.setPadding(dp(8),dp(12),dp(8),dp(12));answerView.setBackground(bg(R.color.sheet,R.color.line,12));body.addView(answerView);gap(12);
  bankView=new LinearLayout(this);bankView.setId(R.id.tile_bank);bankView.setOrientation(LinearLayout.VERTICAL);body.addView(bankView);drawBank();gap(10);body.addView(button("Убрать букву",R.id.undo,false,v->{if(!picked.isEmpty()){picked.remove(picked.size()-1);selection=selection.substring(0,selection.length()-1);drawBank();feedback("Попробуй сложить слово ещё раз.");}}));
 }
 private void drawBank(){
  String target=current().text.toLowerCase(Locale.forLanguageTag("ru"));String bank=new StringBuilder(target).reverse().toString();bankView.removeAllViews();LinearLayout line=null;
  for(int i=0;i<bank.length();i++){if(i%4==0){line=row();LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(8);bankView.addView(line,p);}final int n=i;String letter=String.valueOf(bank.charAt(i));Button b=button(letter,0,false,v->{if(picked.contains(n))return;picked.add(n);selection+=letter;drawBank();if(selection.length()==target.length()){if(selection.equals(target)){mark("assembly");feedback("Буквы на месте. Теперь прочитай слово вслух.");}else feedback("Порядок букв отличается. Убери букву и попробуй иначе.");}});b.setTextSize(34);b.setTypeface(Typeface.SERIF);b.setMinHeight(dp(68));b.setContentDescription("Буква "+letter+", плитка "+i);b.setEnabled(!picked.contains(i));b.setAlpha(b.isEnabled()?1:.28f);inRow(line,b,i%4==3);}
  answerView.setText(selection.isEmpty()?"·  ·  ·":selection);
 }
 private void audioControls(Course.Task t){
  gap(14);LinearLayout capture=row();recordButton=button("Записать себя",R.id.record,true,v->{if(recorder!=null&&!recordingModel)stopRecording(true);else askAudio(1);});playButton=button("Слушать",R.id.play,false,v->play(childClip));playButton.setEnabled(childClip!=null);inRow(capture,recordButton,false);inRow(capture,playButton,true);body.addView(capture);
  recordStatus=text("Запись остаётся на устройстве. Не оценивает произношение.",13,R.color.muted,false);recordStatus.setId(R.id.record_status);body.addView(recordStatus);gap(10);
  if(t.kind.equals("read")){
   speechButton=button("Проверить текст голосом",R.id.speech,false,v->{if(recognizing){recognizer.stopListening();feedback("Завершаем распознавание…");}else askSpeech();});body.addView(speechButton);gap(8);
  }
  LinearLayout models=row();modelButton=button("Записать образец",R.id.record_model,false,v->{assisted=true;if(recorder!=null&&recordingModel)stopRecording(true);else askAudio(2);});playModelButton=button("Слушать образец",R.id.play_model,false,v->{assisted=true;play(adultClip);});playModelButton.setEnabled(adultClip!=null);inRow(models,modelButton,false);inRow(models,playModelButton,true);body.addView(models);
  if(t.kind.equals("read")){gap(8);body.addView(button("Произнести слово",R.id.sample,false,v->speakSample()));}
 }
 private void mark(String kind){if(customText.isEmpty())prefs.edit().putString(key(),kind+(assisted?"_help":"")).apply();}
 private void feedback(String message){lastFeedback=message;if(feedbackView!=null)feedbackView.setText(message);}
 private void advance(int delta){leaveExercise();resetExercise();task+=delta;if(task<0&&lesson>0){lesson--;task=Course.LESSONS.get(lesson).tasks.size()-1;}if(task>=Course.LESSONS.get(lesson).tasks.size()){lesson++;task=0;}if(lesson>=Course.LESSONS.size()){lesson=Course.LESSONS.size()-1;task=Course.LESSONS.get(lesson).tasks.size()-1;screen=0;persist();render();new AlertDialog.Builder(this).setTitle("Вводные занятия пройдены").setMessage("Можно вернуться к любому слову. Ручные отметки и совпадение текста не являются проверкой всех навыков чтения.").setPositiveButton("К содержанию",null).show();return;}persist();render();}
 private void parents(){
  label("ДЛЯ ВЗРОСЛОГО");body.addView(text("Занимаемся спокойно",30,R.color.green,true));gap(12);body.addView(text("Сначала произнесите слово и выделите звук. Затем покажите букву, помогите соединить звуки и прочитать целое слово. Здесь нет таймера, штрафов и автоматического переворачивания страниц.",16,R.color.ink,false));gap(16);
  Switch big=new Switch(this);big.setId(R.id.large_text);big.setText("Крупнее текст чтения");big.setTextColor(c(R.color.ink));big.setTextSize(17);big.setMinHeight(dp(56));big.setChecked(large);big.setOnCheckedChangeListener((b,value)->{large=value;persist();});body.addView(big);
  Switch color=new Switch(this);color.setId(R.id.vowels);color.setText("Выделять гласные цветом");color.setTextColor(c(R.color.ink));color.setTextSize(17);color.setMinHeight(dp(56));color.setChecked(vowels);color.setOnCheckedChangeListener((b,value)->{vowels=value;persist();});body.addView(color);gap(12);
  body.addView(text("Своя строка из книги",24,R.color.green,true));gap(8);EditText input=new EditText(this);input.setId(R.id.custom_input);input.setHint("Например: Мама мыла раму.");input.setTextColor(c(R.color.ink));input.setTextSize(20);input.setMinHeight(dp(64));input.setMaxLines(3);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});input.setText(prefs.getString("custom_last",""));body.addView(input);gap(8);body.addView(button("Читать свою строку",R.id.custom_go,true,v->{String s=input.getText().toString().trim().replaceAll("\\s+"," ");if(s.isEmpty()){input.setError("Введите слово или строку");return;}if(!s.matches("[А-Яа-яЁё\\s.,!?—:;«»\\-]+")){input.setError("Нужна строка на русском языке");return;}((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);leaveExercise();resetExercise();customText=s;screen=1;prefs.edit().putString("custom_last",s).apply();render();}));gap(22);
  body.addView(text("Микрофон и проверка",24,R.color.green,true));gap(8);
  String availability=SpeechRecognizer.isRecognitionAvailable(this)?"Системный распознаватель найден. Русский язык и качество детской речи нужно проверять на этом устройстве.":"Системный распознаватель не найден. Запись и прослушивание доступны независимо от него; автоматическая проверка текста недоступна.";
  TextView diag=text(availability,16,R.color.ink,false);diag.setId(R.id.microphone_status);body.addView(diag);gap(8);body.addView(text("Сравнение текста не проверяет отдельные звуки. Перед распознаванием приложение отдельно спрашивает согласие: установленный речевой сервис может передавать голос на свой сервер. Для записи и уроков интернет не нужен. Записи текущего задания удаляются при выходе из него.",14,R.color.muted,false));gap(18);
  body.addView(text("Об этой версии",24,R.color.green,true));gap(8);body.addView(text("Версия 1.0.0-native. Нативные Android Views и XML; без веб-движка, рекламы, аналитики и Google Play Services. 9 вводных занятий, 54 задания. Это учебная адаптация, а не цифровая копия всего букваря 1955 года. Точность проверки детского чтения не валидирована.",14,R.color.muted,false));gap(18);
  body.addView(button("Очистить отметки занятий",R.id.reset_progress,false,v->new AlertDialog.Builder(this).setTitle("Удалить отметки?").setMessage("Будут удалены только отметки и место продолжения в этом приложении.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{SharedPreferences.Editor e=prefs.edit();for(String k:prefs.getAll().keySet())if(k.startsWith("done_"))e.remove(k);e.remove("started").putInt("lesson",0).putInt("task",0).apply();lesson=0;task=0;goHome();}).show()));
 }
 private void askAudio(int type){
  stopPlayback();cancelSpeech();pendingAudio=type;
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_AUDIO);return;}
  pendingAudio=0;startRecording(type==2);
 }
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request!=REQUEST_AUDIO)return;int action=pendingAudio;pendingAudio=0;if(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED){feedback("Микрофон не разрешён. Чтение и задания доступны без него. Доступ можно разрешить в настройках приложения.");return;}if(screen!=1)return;if(action==3)startRecognition();else if(action==1||action==2)startRecording(action==2);}
 private void startRecording(boolean model){
  stopRecording(false);stopPlayback();cancelSpeech();recordingModel=model;
  File old=model?adultClip:childClip;if(old!=null)old.delete();if(model)adultClip=null;else childClip=null;
  File clip=new File(getCacheDir(),model?"adult-sample.m4a":"child-reading.m4a");
  try{recorder=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioSamplingRate(44100);recorder.setAudioEncodingBitRate(96000);recorder.setOutputFile(clip.getAbsolutePath());recorder.prepare();recorder.start();recordStarted=System.currentTimeMillis();if(model)adultClip=clip;else childClip=clip;
   if(recordButton!=null)recordButton.setText(model?"Записать себя":"Остановить запись");if(modelButton!=null)modelButton.setText(model?"Остановить образец":"Записать образец");if(playButton!=null)playButton.setEnabled(false);if(playModelButton!=null)playModelButton.setEnabled(false);if(recordStatus!=null)recordStatus.setText(model?"Записывается образец взрослого…":"Записывается голос ребёнка…");feedback("Записываем. Нажми «Остановить» после ответа. Лимит — 12 секунд.");recordTimeout=()->stopRecording(true);handler.postDelayed(recordTimeout,12000);
  }catch(Exception e){releaseRecorder();clip.delete();if(model)adultClip=null;else childClip=null;refreshAudioButtons();feedback("Запись не началась: микрофон недоступен или занят. Прочитайте взрослому.");Log.w("BukvarAudio","record-start-failed",e);}
 }
 private void stopRecording(boolean report){
  if(recorder==null)return;if(recordTimeout!=null)handler.removeCallbacks(recordTimeout);recordTimeout=null;boolean ok=false;
  try{recorder.stop();ok=true;}catch(RuntimeException e){Log.w("BukvarAudio","record-stop-failed",e);}finally{releaseRecorder();}
  File clip=recordingModel?adultClip:childClip;ok=ok&&clip!=null&&clip.length()>128;
  if(!ok){if(clip!=null)clip.delete();if(recordingModel)adultClip=null;else childClip=null;}
  if(ok)Log.i("BukvarAudio","RECORDING_OK bytes="+clip.length()+" durationMs="+(System.currentTimeMillis()-recordStarted));refreshAudioButtons();if(recordStatus!=null)recordStatus.setText(ok?"Запись готова. Хранится только на этом устройстве.":"Запись не сохранена.");if(report)feedback(ok?"Запись готова. Послушайте вместе. Правильность произношения подтверждает взрослый.":"Запись слишком короткая или микрофон недоступен. Попробуйте ещё раз.");
 }
 private void releaseRecorder(){if(recorder!=null){try{recorder.release();}catch(Exception ignored){}recorder=null;}}
 private void refreshAudioButtons(){if(recordButton!=null)recordButton.setText("Записать себя");if(modelButton!=null)modelButton.setText("Записать образец");if(playButton!=null)playButton.setEnabled(childClip!=null);if(playModelButton!=null)playModelButton.setEnabled(adultClip!=null);}
 private void play(File clip){stopRecording(false);cancelSpeech();stopPlayback();if(clip==null||!clip.isFile()){feedback("Сначала сделайте запись.");return;}try{player=new MediaPlayer();player.setDataSource(clip.getAbsolutePath());player.prepare();player.setOnCompletionListener(p->{Log.i("BukvarAudio","PLAYBACK_OK");stopPlayback();});player.start();feedback("Слушаем запись. Автоматической оценки звуков нет.");}catch(Exception e){stopPlayback();feedback("Не удалось прослушать запись. Запишите ещё раз.");}}
 private void stopPlayback(){if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}if(tts!=null)tts.stop();}
 private void askSpeech(){
  stopRecording(false);stopPlayback();
  if(!SpeechRecognizer.isRecognitionAvailable(this)){feedback("На устройстве нет системного распознавателя. Запишите чтение и проверьте вместе со взрослым.");return;}
  if(!cloudConsent){new AlertDialog.Builder(this).setTitle("Проверка текста голосом").setMessage("Системный речевой сервис может отправлять голос на сервер своего провайдера. Приложение сравнит только текст — не правильность каждого звука. Взрослый согласен включить распознавание на время этого запуска?").setNegativeButton("Не включать",null).setPositiveButton("Разрешить",(d,w)->{cloudConsent=true;askSpeech();}).show();return;}
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){pendingAudio=3;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_AUDIO);return;}startRecognition();
 }
 private void startRecognition(){
  cancelSpeech();if(!current().kind.equals("read"))return;final int token=++epoch;final Course.Task expected=current();recognizing=true;
  try{recognizer=SpeechRecognizer.createSpeechRecognizer(this);recognizer.setRecognitionListener(new RecognitionListener(){
   public void onReadyForSpeech(Bundle p){if(token==epoch)feedback("Слушаем. Прочитай строку вслух.");}
   public void onBeginningOfSpeech(){}public void onRmsChanged(float n){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){if(token==epoch)feedback("Разбираем услышанное…");}
   public void onPartialResults(Bundle b){}public void onEvent(int type,Bundle b){}
   public void onError(int error){if(token!=epoch)return;cancelSpeech();String message=switch(error){case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"Микрофон не разрешён.";case SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT->"Речевой сервис недоступен по сети.";case SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"Не удалось разобрать речь.";case SpeechRecognizer.ERROR_AUDIO->"Микрофон недоступен или занят.";default->"Речевой сервис не завершил ответ (код "+error+").";};feedback(message+" Ответ ребёнка не оценивался. Можно записать и послушать вместе.");}
   public void onResults(Bundle results){if(token!=epoch)return;ArrayList<String> texts=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);String heard=texts==null||texts.isEmpty()?"":texts.get(0);cancelSpeech();ReadingRules.Result result=ReadingRules.compare(expected.text,heard,expected.syllables);switch(result){case EXACT-> {mark("text");feedback("Текст совпал: «"+heard+"». Отдельные звуки не оценивались.");}case SYLLABLES->feedback("По слогам совпало: «"+heard+"». Теперь попробуйте прочитать целиком.");case EMPTY->feedback("Не удалось разобрать речь. Тишина не засчитана.");case DIFFERENT->feedback("Услышано: «"+heard+"». Текст не совпал. Мог ошибиться распознаватель; прочитайте ещё раз или проверьте со взрослым.");}}
  });
  Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU");intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
  // Never provide the expected answer as a recognition prompt or biasing string.
  recognizer.startListening(intent);if(speechButton!=null)speechButton.setText("Завершить ответ");feedback("Открываем микрофон для распознавания…");speechTimeout=()->{if(token==epoch){cancelSpeech();feedback("Речевой сервис не ответил. Задание не засчитано.");}};handler.postDelayed(speechTimeout,25000);
  }catch(Exception e){cancelSpeech();feedback("Не удалось запустить распознавание. Используйте запись и проверку взрослым.");}
 }
 private void cancelSpeech(){epoch++;recognizing=false;if(speechTimeout!=null)handler.removeCallbacks(speechTimeout);speechTimeout=null;if(recognizer!=null){try{recognizer.cancel();recognizer.destroy();}catch(Exception ignored){}recognizer=null;}if(speechButton!=null)speechButton.setText("Проверить текст голосом");}
 private void speakSample(){assisted=true;stopRecording(false);cancelSpeech();stopPlayback();if(tts==null){tts=new TextToSpeech(this,status->{ttsReady=status==TextToSpeech.SUCCESS;if(ttsReady)doSpeak();else feedback("Синтезатор речи не найден. Запишите образец взрослого.");});}else if(ttsReady)doSpeak();else feedback("Голос ещё не готов. Повторите нажатие.");}
 private void doSpeak(){if(screen!=1||!current().kind.equals("read"))return;Locale ru=Locale.forLanguageTag("ru-RU");if(tts.isLanguageAvailable(ru)<TextToSpeech.LANG_AVAILABLE){feedback("Русский голос не установлен. Запишите образец взрослого.");return;}tts.setLanguage(ru);tts.setSpeechRate(.8f);tts.speak(current().text,TextToSpeech.QUEUE_FLUSH,null,"sample");feedback("Включён образец. Чтение будет отмечено как «с подсказкой».");}
 private void leaveExercise(){stopRecording(false);stopPlayback();cancelSpeech();pendingAudio=0;if(childClip!=null)childClip.delete();if(adultClip!=null)adultClip.delete();childClip=null;adultClip=null;}
 @Override public void onBackPressed(){if(screen!=0)goHome();else super.onBackPressed();}
 @Override protected void onPause(){if(recorder!=null)stopRecording(true);if(recognizing){cancelSpeech();feedback("Прослушивание остановлено: приложение скрыто. Ничего не засчитано.");}stopPlayback();super.onPause();}
 @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("screen",screen);state.putInt("lesson",lesson);state.putInt("task",task);state.putString("custom",customText);state.putString("selection",selection);state.putString("feedback",lastFeedback);state.putBoolean("assisted",assisted);state.putBoolean("split",split);state.putBoolean("hint",hintVisible);int[] a=new int[picked.size()];for(int i=0;i<a.length;i++)a[i]=picked.get(i);state.putIntArray("picked",a);}
 @Override protected void onDestroy(){leaveExercise();if(tts!=null){tts.shutdown();tts=null;}handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
