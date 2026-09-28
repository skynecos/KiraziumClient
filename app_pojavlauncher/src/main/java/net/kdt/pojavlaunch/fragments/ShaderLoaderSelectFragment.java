package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.instances.SelectedProfileInfo;

/** Lets the user choose the shader loader compatibility target before browsing shaders. */
public class ShaderLoaderSelectFragment extends Fragment {
    public static final String TAG = "ShaderLoaderSelectFragment";

    public ShaderLoaderSelectFragment() {
        super(R.layout.fragment_shader_loader_select);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.shader_loader_back)
                .setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));

        TextView subtitle = view.findViewById(R.id.shader_loader_subtitle);
        try {
            Instance instance = Instances.loadSelectedInstance();
            if (instance != null) {
                SelectedProfileInfo profile = SelectedProfileInfo.resolve(instance);
                subtitle.setText(getString(R.string.shader_loader_profile_subtitle,
                        instance.name, profile.gameVersion));
            }
        } catch (Exception ignored) {
            // Keep the generic subtitle when the selected profile cannot be resolved.
        }

        view.findViewById(R.id.shader_loader_iris).setOnClickListener(v ->
                openShaderBrowser(ShaderPackFragment.LOADER_IRIS));
        view.findViewById(R.id.shader_loader_optifine).setOnClickListener(v ->
                openShaderBrowser(ShaderPackFragment.LOADER_OPTIFINE));
    }

    private void openShaderBrowser(String loader) {
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) {
            Toast.makeText(requireContext(), R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }

        Tools.swapFragment(requireActivity(), ShaderPackFragment.class,
                ShaderPackFragment.TAG, ShaderPackFragment.createArgs(loader));
    }
}
