package com.winlator;

import android.content.Intent;
import android.os.Bundle;
import android.os.StatFs;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xenvironment.ImageFs;
import java.io.File;
import java.util.concurrent.Executors;

/** Initial ARM32 TV test: a private runtime and embedded demo, with no uploads. */
public class TVStartActivity extends AppCompatActivity {
    private TextView status;
    private Button start;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 32);
        TextView title = new TextView(this);
        title.setText("DataExpress TV — ARM32, тестовая сборка");
        title.setTextSize(26);
        layout.addView(title);
        status = new TextView(this);
        status.setTextSize(19);
        status.setText("Первый запуск подготовит Wine32. Для работы с таблицами удобнее USB-мышь и клавиатура. Результат запуска ещё проверяется.");
        layout.addView(status);
        start = new Button(this);
        start.setText("Открыть учебную базу");
        start.setOnClickListener(v -> prepare());
        layout.addView(start);
        setContentView(layout);
        start.requestFocus();
        ProcessHelper.debugCallback = line -> Log.i("DataExpressTV", line);
    }
    private void setStatus(String message) {
        runOnUiThread(() -> status.setText(message));
        Log.i("DataExpressTV", message);
    }
    private void prepare() {
        start.setEnabled(false);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                long preparationStart = android.os.SystemClock.elapsedRealtime();
                ImageFs image = ImageFs.find(this);
                File root = image.getRootDir();
                if (!image.isValid()) {
                    long available = new StatFs(getFilesDir().getPath()).getAvailableBytes();
                    if (available < 900L*1024*1024) throw new IllegalStateException("Для подготовки требуется не менее 900 МБ свободного места");
                    setStatus("Подготовка 32-битной среды. Первый запуск может занять несколько минут…");
                    root.mkdirs();
                    if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "imagefs.tzst", root))
                        throw new IllegalStateException("Не удалось распаковать среду");
                    image.createImgVersionFile(4);
                    Log.i("DataExpressTV", "Runtime extracted ms="+(android.os.SystemClock.elapsedRealtime()-preparationStart));
                }
                else Log.i("DataExpressTV", "Using cached runtime");
                FileUtils.copy(this, "tv-start.sh", new File(root, "opt/dataexpress/start-tv.sh"));
                File home = new File(root, "home/xuser-1");
                home.mkdirs();
                File link = new File(root, "home/xuser");
                if (!link.exists()) FileUtils.symlink("./xuser-1", link.getPath());
                Container container = new Container(1);
                container.setRootDir(home);
                container.setName("DataExpress TV ARM32");
                container.setScreenSize("960x540");
                container.setGraphicsDriver("gdi");
                container.setAudioDriver("disabled");
                container.setDXWrapper("wined3d");
                container.setWinComponents(Container.FALLBACK_WINCOMPONENTS);
                container.setStopServicesOnStartup(false);
                container.setDrives("");
                container.setEnvVars("WINEDLLPATH=/usr/lib/i386-linux-gnu/wine WINEDLLOVERRIDES=winebus.sys,wineusb.sys,winebth.sys=d;zlib1=n,b WINEARCH=win32 WINEESYNC=0 WINEDEBUG=-all,err+all BOX86_DYNAREC=1 BOX86_LOG=1 BOX86_LD_LIBRARY_PATH=/usr/lib/i386-linux-gnu:/lib/i386-linux-gnu");
                container.putExtra("tv32", "1");
                container.saveData();
                setStatus("Запуск Wine32 и DataExpress…");
                runOnUiThread(() -> {
                    start.setEnabled(true);
                    Intent intent = new Intent(this, XServerDisplayActivity.class);
                    intent.putExtra("container_id", 1);
                    startActivity(intent);
                });
            } catch (Exception error) {
                Log.e("DataExpressTV", "Preparation failed", error);
                setStatus("Ошибка: "+error.getMessage());
                runOnUiThread(() -> start.setEnabled(true));
            }
        });
    }
}
