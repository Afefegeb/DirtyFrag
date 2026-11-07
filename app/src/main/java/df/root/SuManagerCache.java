package df.root;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which installed apps ship a ksud, remembered between scans.
 *
 * <p>Probing an app costs reads of library files, and the picker used to pay
 * that for every installed app on every resume. Nothing about an app's verdict
 * can change without the app itself changing, and the package manager already
 * says when it did: versionCode plus lastUpdateTime. So a scan only probes the
 * apps whose stamp moved - the rest are answered from the cache without a
 * single file read. The first scan after an install still probes everything,
 * which is why {@link ExploitRunner#findKsud} refuses to read a library whose
 * name no build would have renamed the daemon to.
 *
 * <p>The cache is a hint for the picker only: {@code ExploitRunner.run()}
 * re-probes the selected manager before staging, so a stale entry can make the
 * list show a manager that has gone, never stage the wrong file.
 */
final class SuManagerCache {

    private static final String PREFS = "dfroot_scan";
    /** Package names that ship a ksud, '\n' separated. */
    private static final String KEY_HITS = "hits";
    /** Per-app "versionCode:lastUpdateTime" stamp, keyed by package name. */
    private static final String KEY_STAMP = "stamp:";

    private SuManagerCache() {}

    /**
     * Package names of installed apps that ship a ksud, sorted for display.
     * Blocks while the first scan reads libraries; call it off the main thread.
     */
    static synchronized List<String> scan(Context ctx) {
        String self = ctx.getPackageName();
        PackageManager pm = ctx.getPackageManager();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();

        Set<String> hits = new HashSet<>();
        for (String p : sp.getString(KEY_HITS, "").split("\n")) {
            if (!p.isEmpty()) hits.add(p);
        }

        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        Set<String> installed = new HashSet<>();
        List<ApplicationInfo> changed = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(self)) continue;
            installed.add(ai.packageName);
            String stamp = ai.longVersionCode + ":" + ai.lastUpdateTime;
            // A missing stamp means this app was never probed - the same path
            // as a changed one, so an upgrade and a first sight cost the same.
            if (!stamp.equals(sp.getString(KEY_STAMP + ai.packageName, null))) {
                changed.add(ai);
            }
        }

        // Forget apps that were uninstalled, and re-probe only what moved.
        Set<String> gone = new HashSet<>(hits);
        gone.addAll(stamps(sp));
        gone.removeAll(installed);
        for (String p : gone) {
            hits.remove(p);
            ed.remove(KEY_STAMP + p);
        }
        for (ApplicationInfo ai : changed) {
            hits.remove(ai.packageName);
            if (ExploitRunner.findKsud(ai) != null) hits.add(ai.packageName);
            ed.putString(KEY_STAMP + ai.packageName,
                    ai.longVersionCode + ":" + ai.lastUpdateTime);
        }

        // Keep the hit order stable so the cache does not churn on every write.
        List<String> ordered = sorted(hits);
        ed.putString(KEY_HITS, String.join("\n", ordered));
        ed.apply();
        return ordered;
    }

    /** Package names that carry a cached stamp, stripped of the key prefix. */
    private static Set<String> stamps(SharedPreferences sp) {
        Set<String> out = new HashSet<>();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(KEY_STAMP)) out.add(key.substring(KEY_STAMP.length()));
        }
        return out;
    }

    private static List<String> sorted(Set<String> names) {
        List<String> out = new ArrayList<>(names);
        Collections.sort(out);
        return out;
    }
}
