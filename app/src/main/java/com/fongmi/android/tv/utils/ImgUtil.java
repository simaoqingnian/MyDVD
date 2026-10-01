package com.fongmi.android.tv.utils;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.CustomTarget;
import com.github.catvod.utils.Json;
import com.google.common.net.HttpHeaders;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jahirfiquitiva.libs.textdrawable.TextDrawable;

public class ImgUtil {

    private static final Set<String> preferDefaultPort = ConcurrentHashMap.newKeySet();

    public static void logo(ImageView view) {
        try {
            Glide.with(view).load(UrlUtil.convert(VodConfig.get().getConfig().getLogo())).circleCrop().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL).error(R.drawable.ic_logo).into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String url, CustomTarget<Bitmap> target) {
        try {
            Glide.with(App.get()).asBitmap().load(getUrl(url)).override(ResUtil.dp2px(96), ResUtil.dp2px(96)).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, int width, int height, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(width, height).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void preload(Context context, String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).preload();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String text, String url, ImageView view) {
        load(text, url, view, true);
    }

    public static void load(String text, String url, ImageView view, boolean vod) {
        load(text, url, view, vod, 0, 0);
    }

    public static void load(String text, String url, ImageView view, int width, int height) {
        load(text, url, view, true, width, height);
    }

    public static void load(String text, String url, ImageView view, boolean vod, int width, int height) {
        view.setScaleType(vod ? CENTER_CROP : FIT_CENTER);
        if (!vod) view.setVisibility(TextUtils.isEmpty(url) ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(url)) view.setImageDrawable(getTextDrawable(text, vod));
        else try {
            String fallback = defaultPortUrl(url);
            String route = fallback == null ? "" : portRoute(url);
            boolean useFallback = fallback != null && preferDefaultPort.contains(route);
            String first = useFallback ? fallback : url;
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(first));
            if (fallback != null && !useFallback) {
                RequestBuilder<Drawable> retry = Glide.with(view).load(getUrl(fallback))
                        .listener(getListener(text, view, vod, false, () -> preferDefaultPort.add(route)));
                if (width > 0 && height > 0) retry.override(width, height);
                if (vod) retry.centerCrop();
                else retry.fitCenter();
                builder.listener(getListener(text, view, vod, true, null)).error(retry);
            } else {
                builder.listener(getListener(text, view, vod, false, null));
            }
            if (width > 0 && height > 0) builder.override(width, height);
            if (vod) builder.centerCrop().into(view);
            else builder.fitCenter().into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static Object getUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        url = UrlUtil.convert(url.trim());
        if (url.startsWith("data:")) return url;
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        String[] parts = url.split("@(?=(?:Headers|Cookie|Referer|User-Agent)=)");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("Headers=")) addHeader(builder, part.substring(8));
            else if (part.startsWith("Cookie=")) builder.addHeader(HttpHeaders.COOKIE, part.substring(7));
            else if (part.startsWith("Referer=")) builder.addHeader(HttpHeaders.REFERER, part.substring(8));
            else if (part.startsWith("User-Agent=")) builder.addHeader(HttpHeaders.USER_AGENT, part.substring(11));
        }
        String address = parts[0];
        if (address.startsWith("//")) address = "https:" + address;
        return TextUtils.isEmpty(address) ? null : new GlideUrl(address, builder.build());
    }

    private static void addHeader(LazyHeaders.Builder builder, String header) {
        Map<String, String> map = Json.toMap(Json.parse(header));
        for (Map.Entry<String, String> entry : map.entrySet()) builder.addHeader(UrlUtil.fixHeader(entry.getKey()), entry.getValue());
    }

    private static Drawable getTextDrawable(String text, boolean vod) {
        TextDrawable.Builder builder = new TextDrawable.Builder();
        text = TextUtils.isEmpty(text) ? "！" : text.substring(0, 1);
        if (vod) builder.buildRect(text, ColorGenerator.get400(text));
        return builder.buildRoundRect(text, ColorGenerator.get400(text), ResUtil.dp2px(4));
    }

    private static String defaultPortUrl(String url) {
        try {
            int marker = url.indexOf("@Headers=");
            for (String suffix : new String[]{"@Cookie=", "@Referer=", "@User-Agent="}) {
                int index = url.indexOf(suffix);
                if (index >= 0 && (marker < 0 || index < marker)) marker = index;
            }
            String address = marker < 0 ? url : url.substring(0, marker);
            Uri uri = Uri.parse(address);
            int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443
                    : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
            if (defaultPort < 0 || uri.getPort() <= 0 || uri.getPort() == defaultPort) return null;
            String authority = uri.getEncodedAuthority();
            String port = ":" + uri.getPort();
            if (authority == null || !authority.endsWith(port)) return null;
            String resolved = uri.buildUpon().encodedAuthority(authority.substring(0, authority.length() - port.length())).build().toString();
            return marker < 0 ? resolved : resolved + url.substring(marker);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String portRoute(String url) {
        Uri uri = Uri.parse(url);
        return uri.getScheme() + "://" + uri.getEncodedAuthority();
    }

    private static RequestListener<Drawable> getListener(String text, ImageView view, boolean vod,
                                                          boolean hasFallback, Runnable onReady) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                if (hasFallback) return false;
                view.setImageDrawable(getTextDrawable(text, vod));
                return true;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                if (onReady != null) onReady.run();
                return false;
            }
        };
    }
}
