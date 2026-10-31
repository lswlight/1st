package com.lsw.fermatainstaller;

import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/** Fermata 최신판 다운로드와 Shizuku 를 통한 '플레이스토어 출처' 설치. */
final class Installer {
	static final String PLAY_STORE = "com.android.vending";
	static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
	static final String FERMATA_PREFIX = "me.aap.fermata";
	private static final String RELEASE_API =
			"https://api.github.com/repos/AndreyPavlenko/Fermata/releases/latest";

	interface Progress {
		void onProgress(String message);
	}

	static final class Release {
		final String tag;
		final String assetName;
		final String url;
		final long size;

		Release(String tag, String assetName, String url, long size) {
			this.tag = tag;
			this.assetName = assetName;
			this.url = url;
			this.size = size;
		}
	}

	private Installer() {
	}

	// ---------------------------------------------------------------- 다운로드

	/** 공식 GitHub 최신 릴리스에서 이 휴대폰에 맞는 Fermata Auto APK 를 고른다. */
	static Release findLatest() throws Exception {
		JSONObject json = new JSONObject(readText(RELEASE_API));
		String tag = json.optString("tag_name", "?");
		JSONArray assets = json.getJSONArray("assets");
		String abi = abiKeyword();

		JSONObject best = null;
		int bestScore = -1;
		for (int i = 0; i < assets.length(); i++) {
			JSONObject a = assets.getJSONObject(i);
			String name = a.getString("name").toLowerCase(Locale.ROOT);
			if (!name.endsWith(".apk") || !name.contains("auto")) continue;
			int score = 1;
			if (name.contains("universal")) score += 1;
			if (abi != null && name.contains(abi)) score += 4;
			if (score > bestScore) {
				bestScore = score;
				best = a;
			}
		}
		if (best == null) throw new IOException("최신 릴리스(" + tag + ")에서 Auto 버전 APK 를 찾지 못했습니다");
		return new Release(tag, best.getString("name"), best.getString("browser_download_url"),
				best.optLong("size", -1));
	}

	/** 휴대폰 CPU 종류를 Fermata 파일명에 쓰이는 단어로 바꾼다. */
	private static String abiKeyword() {
		if (Build.SUPPORTED_ABIS.length == 0) return null;
		String abi = Build.SUPPORTED_ABIS[0];
		if (abi.startsWith("arm64")) return "arm64";
		if (abi.startsWith("armeabi")) return "arm";
		if (abi.equals("x86_64")) return "x86_64";
		if (abi.equals("x86")) return "x86";
		return null;
	}

	static File download(Context ctx, Release r, Progress progress) throws IOException {
		File out = new File(ctx.getCacheDir(), "fermata.apk");
		HttpURLConnection c = open(r.url);
		try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
			long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.size;
			byte[] buf = new byte[64 * 1024];
			long done = 0;
			long lastReport = 0;
			int n;
			while ((n = in.read(buf)) > 0) {
				os.write(buf, 0, n);
				done += n;
				if (done - lastReport > 1_000_000) {
					lastReport = done;
					progress.onProgress(total > 0
							? String.format(Locale.KOREA, "다운로드 중… %d%%", done * 100 / total)
							: String.format(Locale.KOREA, "다운로드 중… %.1f MB", done / 1e6));
				}
			}
		} finally {
			c.disconnect();
		}
		return out;
	}

	/** 사용자가 고른 파일(content://)을 캐시로 복사한다. */
	static File copyToCache(Context ctx, InputStream in) throws IOException {
		File out = new File(ctx.getCacheDir(), "fermata.apk");
		try (InputStream src = in; OutputStream os = new FileOutputStream(out)) {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = src.read(buf)) > 0) os.write(buf, 0, n);
		}
		return out;
	}

	private static HttpURLConnection open(String url) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setInstanceFollowRedirects(true);
		c.setConnectTimeout(15_000);
		c.setReadTimeout(30_000);
		c.setRequestProperty("User-Agent", "FermataInstaller");
		c.setRequestProperty("Accept", "application/vnd.github+json, */*");
		int code = c.getResponseCode();
		if (code >= 400) {
			c.disconnect();
			throw new IOException("서버 응답 오류 " + code + " (" + url + ")");
		}
		return c;
	}

	private static String readText(String url) throws IOException {
		HttpURLConnection c = open(url);
		try (InputStream in = c.getInputStream()) {
			return readAll(in);
		} finally {
			c.disconnect();
		}
	}

	// ---------------------------------------------------------------- APK 정보

	/** APK 파일의 패키지 이름. 올바른 APK 가 아니면 null. */
	static String packageNameOf(Context ctx, File apk) {
		PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
		return pi == null ? null : pi.packageName;
	}

	/** 휴대폰에 설치된 Fermata 패키지들. */
	static List<String> installedFermata(Context ctx) {
		List<String> result = new ArrayList<>();
		for (PackageInfo pi : ctx.getPackageManager().getInstalledPackages(0)) {
			if (pi.packageName.startsWith(FERMATA_PREFIX)) result.add(pi.packageName);
		}
		return result;
	}

	/** 설치 출처 설명. 설치 안 됐으면 null. */
	static String describeInstallSource(Context ctx, String pkg) {
		try {
			InstallSourceInfo info = ctx.getPackageManager().getInstallSourceInfo(pkg);
			return "설치 프로그램: " + info.getInstallingPackageName()
					+ "\n설치 시작한 앱: " + info.getInitiatingPackageName();
		} catch (PackageManager.NameNotFoundException e) {
			return null;
		}
	}

	static boolean isInstalled(Context ctx, String pkg) {
		try {
			ctx.getPackageManager().getPackageInfo(pkg, 0);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- Shizuku 설치

	/**
	 * Shizuku(셸 권한)로 'pm install -i com.android.vending' 을 실행한다.
	 * 셸은 앱 내부 파일을 읽을 수 없으므로 APK 를 표준 입력으로 넘긴다.
	 *
	 * @return pm 명령의 출력 (성공 시 "Success")
	 */
	static String installAsPlayStore(File apk) throws Exception {
		String[] cmd = {"pm", "install", "-r", "-i", PLAY_STORE, "-S", String.valueOf(apk.length())};
		Process p = newShizukuProcess(cmd);
		try (OutputStream os = p.getOutputStream(); InputStream in = new FileInputStream(apk)) {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
		}
		String out = readAll(p.getInputStream()).trim();
		String err = readAll(p.getErrorStream()).trim();
		int code = p.waitFor();
		String all = (out + "\n" + err).trim();
		if (code != 0 || !all.contains("Success")) throw new IOException("설치 실패 (코드 " + code + ")\n" + all);
		return all;
	}

	/** Shizuku.newProcess 는 API 에서 숨겨져 있어 리플렉션으로 호출한다. */
	private static Process newShizukuProcess(String[] cmd) throws Exception {
		Method m = Shizuku.class.getDeclaredMethod("newProcess",
				String[].class, String[].class, String.class);
		m.setAccessible(true);
		return (Process) m.invoke(null, cmd, null, null);
	}

	private static String readAll(InputStream in) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
		return bos.toString(StandardCharsets.UTF_8.name());
	}
}
