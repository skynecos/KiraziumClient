package net.kdt.pojavlaunch.fragments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.instances.SelectedProfileInfo;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.KiraziumSecureDownloads;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Modrinth shader browser tied to the selected Kirazium instance. */
public class ShaderPackFragment extends Fragment {
    public static final String TAG = "ShaderPackFragment";
    public static final String ARG_LOADER = "kirazium_shader_loader";
    public static final String LOADER_IRIS = "iris";
    public static final String LOADER_OPTIFINE = "optifine";

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final int RESULT_LIMIT = 30;
    private static final int MAX_API_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ICON_DIMENSION = 1024;
    private static final long MAX_SHADER_PACK_BYTES = 512L * 1024L * 1024L;

    private EditText mSearchInput;
    private ProgressBar mProgress;
    private TextView mStatus;
    private TextView mSubtitle;
    private ShaderPackAdapter mAdapter;
    private int mSearchGeneration;
    private String mLoader = LOADER_IRIS;

    private final LruCache<String, Bitmap> mIconCache = new LruCache<>(40);
    private final Set<String> mInstalledProjects = new HashSet<>();

    public ShaderPackFragment() {
        super(R.layout.fragment_shader_packs);
    }

    public static Bundle createArgs(String loader) {
        Bundle args = new Bundle();
        args.putString(ARG_LOADER, normalizeLoader(loader));
        return args;
    }

    private static String normalizeLoader(String loader) {
        return LOADER_OPTIFINE.equalsIgnoreCase(loader) ? LOADER_OPTIFINE : LOADER_IRIS;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        mLoader = normalizeLoader(args == null ? null : args.getString(ARG_LOADER));

        ImageButton backButton = view.findViewById(R.id.shader_pack_back);
        ImageButton searchButton = view.findViewById(R.id.shader_pack_search_button);
        View installedShadersCard = view.findViewById(R.id.installed_shaders_card);
        RecyclerView list = view.findViewById(R.id.shader_pack_list);
        mSearchInput = view.findViewById(R.id.shader_pack_search);
        mProgress = view.findViewById(R.id.shader_pack_progress);
        mStatus = view.findViewById(R.id.shader_pack_status);
        mSubtitle = view.findViewById(R.id.shader_pack_subtitle);

        mAdapter = new ShaderPackAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(mAdapter);

        backButton.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        installedShadersCard.setOnClickListener(v -> openShaderpacksFolder());
        searchButton.setOnClickListener(v -> searchPacks(mSearchInput.getText().toString()));
        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchPacks(mSearchInput.getText().toString());
                mSearchInput.clearFocus();
                return true;
            }
            return false;
        });

        searchPacks("");
    }

    private void openShaderpacksFolder() {
        try {
            Instance instance = Instances.loadSelectedInstance();
            if (instance == null) throw new IOException("No selected instance");
            File folder = new File(instance.getGameDirectory(), "shaderpacks");
            FileUtils.ensureDirectory(folder);
            Tools.openPath(requireContext(), folder, false);
        } catch (Exception error) {
            Toast.makeText(requireContext(), R.string.shader_pack_download_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void searchPacks(String query) {
        final int generation = ++mSearchGeneration;
        mProgress.setVisibility(View.VISIBLE);
        mStatus.setText(R.string.shader_packs_loading);
        mStatus.setVisibility(View.VISIBLE);

        final String cleanQuery = query == null ? "" : query.trim();
        PojavApplication.sExecutorService.execute(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new IOException("No selected instance");
                SelectedProfileInfo profile = SelectedProfileInfo.resolve(instance);

                // Modrinth search exposes loader/platform tags through the categories facet.
                String facets = "[[\"project_type:shader\"],[\"versions:" +
                        profile.gameVersion + "\"],[\"categories:" + mLoader + "\"]]";
                String url = MODRINTH_API + "/search?limit=" + RESULT_LIMIT +
                        "&index=downloads&query=" + Uri.encode(cleanQuery) +
                        "&facets=" + Uri.encode(facets);

                JSONObject response = new JSONObject(
                        KiraziumSecureDownloads.downloadModrinthString(url, MAX_API_BYTES));
                JSONArray hits = response.optJSONArray("hits");
                List<ShaderPack> packs = new ArrayList<>();
                if (hits != null) {
                    for (int i = 0; i < hits.length(); i++) {
                        JSONObject hit = hits.optJSONObject(i);
                        if (hit == null) continue;
                        String projectId = hit.optString("project_id", "");
                        String title = hit.optString("title", "");
                        if (TextUtils.isEmpty(projectId) || TextUtils.isEmpty(title)) continue;
                        packs.add(new ShaderPack(
                                projectId,
                                title,
                                hit.optString("description", ""),
                                hit.optString("icon_url", ""),
                                hit.optLong("downloads", 0L)));
                    }
                }

                Tools.runOnUiThread(() -> {
                    if (!isAdded() || generation != mSearchGeneration) return;
                    mSubtitle.setText(getString(R.string.shader_packs_profile_subtitle,
                            profile.gameVersion,
                            displayLoaderName()));
                    mProgress.setVisibility(View.GONE);
                    mAdapter.setItems(packs);
                    if (packs.isEmpty()) {
                        mStatus.setText(R.string.shader_packs_empty);
                        mStatus.setVisibility(View.VISIBLE);
                    } else {
                        mStatus.setVisibility(View.GONE);
                    }
                });
            } catch (Exception exception) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || generation != mSearchGeneration) return;
                    mProgress.setVisibility(View.GONE);
                    mAdapter.setItems(new ArrayList<>());
                    mStatus.setText(R.string.shader_packs_error);
                    mStatus.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private String displayLoaderName() {
        return LOADER_OPTIFINE.equals(mLoader) ? "OptiFine" : "Iris";
    }

    private void downloadPack(ShaderPack pack, Button button) {
        button.setEnabled(false);
        button.setText(R.string.shader_pack_downloading);

        PojavApplication.sExecutorService.execute(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new IOException("No selected instance");
                SelectedProfileInfo profile = SelectedProfileInfo.resolve(instance);

                JSONObject file = findCompatibleFile(pack.projectId, profile.gameVersion, mLoader);
                if (file == null) {
                    Tools.runOnUiThread(() -> {
                        if (!isAdded()) return;
                        button.setEnabled(true);
                        button.setText(R.string.shader_pack_download);
                        Toast.makeText(requireContext(),
                                R.string.shader_pack_no_compatible_version,
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                String filename = new File(file.optString("filename", "shader-pack.zip")).getName();
                if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    throw new IOException("Shader pack file is not a ZIP");
                }

                File shaderPacks = new File(instance.getGameDirectory(), "shaderpacks");
                FileUtils.ensureDirectory(shaderPacks);
                File destination = new File(shaderPacks, filename);
                boolean alreadyThere = destination.isFile();

                String downloadUrl = file.getString("url");
                JSONObject hashes = file.optJSONObject("hashes");
                String sha512 = hashes == null ? null : hashes.optString("sha512", null);
                long expectedSize = file.optLong("size", -1L);
                KiraziumSecureDownloads.downloadVerifiedModrinthFile(
                        downloadUrl, destination, sha512, expectedSize, MAX_SHADER_PACK_BYTES);

                mInstalledProjects.add(pack.projectId);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    button.setEnabled(false);
                    button.setText(R.string.shader_pack_installed);
                    CharSequence message = alreadyThere
                            ? getString(R.string.shader_pack_already_installed)
                            : getString(R.string.shader_pack_installed_message, pack.title);
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    button.setEnabled(true);
                    button.setText(R.string.shader_pack_download);
                    Toast.makeText(requireContext(),
                            R.string.shader_pack_download_failed,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private JSONObject findCompatibleFile(String projectId, String gameVersion, String loader)
            throws Exception {
        String versions = "[\"" + gameVersion + "\"]";
        String url = MODRINTH_API + "/project/" + Uri.encode(projectId) + "/version" +
                "?game_versions=" + Uri.encode(versions) +
                "&include_changelog=false";

        JSONArray versionList = new JSONArray(
                KiraziumSecureDownloads.downloadModrinthString(url, MAX_API_BYTES));

        JSONObject selectedVersion = null;
        for (int pass = 0; pass < 2 && selectedVersion == null; pass++) {
            for (int i = 0; i < versionList.length(); i++) {
                JSONObject candidate = versionList.optJSONObject(i);
                if (candidate == null || !versionSupportsLoader(candidate, loader)) continue;
                boolean release = "release".equals(candidate.optString("version_type"));
                if (pass == 0 && !release) continue;
                selectedVersion = candidate;
                break;
            }
        }
        if (selectedVersion == null) return null;

        JSONArray files = selectedVersion.optJSONArray("files");
        if (files == null || files.length() == 0) return null;

        JSONObject firstZip = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String name = file.optString("filename", "").toLowerCase(Locale.ROOT);
            if (!name.endsWith(".zip")) continue;
            if (firstZip == null) firstZip = file;
            if (file.optBoolean("primary", false)) return file;
        }
        return firstZip;
    }

    private boolean versionSupportsLoader(JSONObject version, String loader) {
        JSONArray loaders = version.optJSONArray("loaders");
        if (loaders == null) return false;
        for (int i = 0; i < loaders.length(); i++) {
            if (loader.equalsIgnoreCase(loaders.optString(i, ""))) return true;
        }
        return false;
    }

    private Bitmap decodeSafeIcon(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_ICON_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > MAX_ICON_DIMENSION || bounds.outHeight > MAX_ICON_DIMENSION) {
            return null;
        }
        return BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    private void loadIcon(ShaderPack pack, ImageView imageView) {
        imageView.setTag(pack.projectId);
        imageView.setImageResource(R.drawable.ic_px_image);
        if (TextUtils.isEmpty(pack.iconUrl)) return;

        Bitmap cached = mIconCache.get(pack.iconUrl);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        PojavApplication.sExecutorService.execute(() -> {
            try {
                byte[] data = KiraziumSecureDownloads.downloadModrinthBytes(
                        pack.iconUrl, MAX_ICON_BYTES);
                Bitmap bitmap = decodeSafeIcon(data);
                if (bitmap == null) return;
                mIconCache.put(pack.iconUrl, bitmap);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Object tag = imageView.getTag();
                    if (pack.projectId.equals(tag)) imageView.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // Keep the built-in icon if a remote icon cannot be loaded safely.
            }
        });
    }

    private String formatDownloads(long downloads) {
        if (downloads >= 1_000_000L) {
            return String.format(Locale.getDefault(), "%.1f Mn", downloads / 1_000_000f);
        }
        if (downloads >= 1_000L) {
            return String.format(Locale.getDefault(), "%.1f B", downloads / 1_000f);
        }
        return Long.toString(downloads);
    }

    private final class ShaderPackAdapter extends RecyclerView.Adapter<ShaderPackViewHolder> {
        private final List<ShaderPack> items = new ArrayList<>();

        void setItems(List<ShaderPack> packs) {
            items.clear();
            items.addAll(packs);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ShaderPackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_texture_pack, parent, false);
            return new ShaderPackViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ShaderPackViewHolder holder, int position) {
            ShaderPack pack = items.get(position);
            holder.title.setText(pack.title);
            holder.description.setText(pack.description);
            holder.downloads.setText(getString(
                    R.string.shader_pack_downloads,
                    formatDownloads(pack.downloads)));
            loadIcon(pack, holder.icon);

            boolean installed = mInstalledProjects.contains(pack.projectId);
            holder.download.setEnabled(!installed);
            holder.download.setText(installed
                    ? R.string.shader_pack_installed
                    : R.string.shader_pack_download);
            holder.download.setOnClickListener(v -> downloadPack(pack, holder.download));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class ShaderPackViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView description;
        final TextView downloads;
        final Button download;

        ShaderPackViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.texture_pack_icon);
            title = itemView.findViewById(R.id.texture_pack_item_title);
            description = itemView.findViewById(R.id.texture_pack_item_description);
            downloads = itemView.findViewById(R.id.texture_pack_item_downloads);
            download = itemView.findViewById(R.id.texture_pack_download_button);
        }
    }

    private static final class ShaderPack {
        final String projectId;
        final String title;
        final String description;
        final String iconUrl;
        final long downloads;

        ShaderPack(String projectId, String title, String description, String iconUrl,
                   long downloads) {
            this.projectId = projectId;
            this.title = title;
            this.description = description;
            this.iconUrl = iconUrl;
            this.downloads = downloads;
        }
    }
}
