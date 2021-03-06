package io.particle.android.sdk.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.design.widget.Snackbar.Callback;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.util.Pair;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.AppCompatImageView;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.getbase.floatingactionbutton.AddFloatingActionButton;
import com.getbase.floatingactionbutton.FloatingActionsMenu;
import com.tumblr.bookends.Bookends;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.ParametersAreNonnullByDefault;

import io.particle.android.sdk.DevicesLoader;
import io.particle.android.sdk.DevicesLoader.DevicesLoadResult;
import io.particle.android.sdk.cloud.ParticleCloudException;
import io.particle.android.sdk.cloud.ParticleDevice;
import io.particle.android.sdk.cloud.ParticleEvent;
import io.particle.android.sdk.cloud.ParticleEventHandler;
import io.particle.android.sdk.devicesetup.ParticleDeviceSetupLibrary;
import io.particle.android.sdk.devicesetup.ParticleDeviceSetupLibrary.DeviceSetupCompleteReceiver;
import io.particle.android.sdk.ui.Comparators.BooleanComparator;
import io.particle.android.sdk.ui.Comparators.ComparatorChain;
import io.particle.android.sdk.ui.Comparators.NullComparator;
import io.particle.android.sdk.utils.EZ;
import io.particle.android.sdk.utils.TLog;
import io.particle.android.sdk.utils.ui.Toaster;
import io.particle.android.sdk.utils.ui.Ui;
import io.particle.sdk.app.R;

import static io.particle.android.sdk.utils.Py.list;
import static io.particle.android.sdk.utils.Py.truthy;

//FIXME enabling & disabling system events on each refresh as it collides with fetching devices in parallel
@ParametersAreNonnullByDefault
public class DeviceListFragment extends Fragment
        implements LoaderManager.LoaderCallbacks<DevicesLoadResult> {

    interface Callbacks {
        void onDeviceSelected(ParticleDevice device);
    }

    private static final TLog log = TLog.get(DeviceListFragment.class);

    // A no-op impl of {@link Callbacks}. Used when this fragment is not attached to an activity.
    private static final Callbacks dummyCallbacks = device -> { /* no-op */ };

    private SwipeRefreshLayout refreshLayout;
    private FloatingActionsMenu fabMenu;
    private DeviceListAdapter adapter;
    private Bookends<DeviceListAdapter> bookends;
    // FIXME: naming, document better
    private ProgressBar partialContentBar;
    private boolean isLoadingSnackbarVisible;
    private Queue<Long> subscribeIds = new ConcurrentLinkedQueue<Long>();

    private final ReloadStateDelegate reloadStateDelegate = new ReloadStateDelegate();
    private final Comparator<ParticleDevice> comparator = helpfulOrderDeviceComparator();

    private Callbacks callbacks = dummyCallbacks;
    private DeviceSetupCompleteReceiver deviceSetupCompleteReceiver;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        callbacks = EZ.getCallbacksOrThrow(this, Callbacks.class);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View top = inflater.inflate(R.layout.fragment_device_list2, container, false);

        RecyclerView rv = Ui.findView(top, R.id.device_list);
        rv.setHasFixedSize(true);  // perf. optimization
        LinearLayoutManager layoutManager = new LinearLayoutManager(inflater.getContext());
        rv.setLayoutManager(layoutManager);
        rv.addItemDecoration(new DividerItemDecoration(getContext(), LinearLayout.VERTICAL));

        partialContentBar = (ProgressBar) inflater.inflate(R.layout.device_list_footer, (ViewGroup) top, false);
        partialContentBar.setVisibility(View.INVISIBLE);
        partialContentBar.setLayoutParams(
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        adapter = new DeviceListAdapter(getActivity());
        // Add them as headers / footers
        bookends = new Bookends<>(adapter);
        bookends.addFooter(partialContentBar);

        rv.setAdapter(bookends);
        ItemClickSupport.addTo(rv).setOnItemClickListener((recyclerView, position, v) -> onDeviceRowClicked(position));
        return top;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        fabMenu = Ui.findView(view, R.id.add_device_fab);
        AddFloatingActionButton addPhoton = Ui.findView(view, R.id.action_set_up_a_photon);
        AddFloatingActionButton addCore = Ui.findView(view, R.id.action_set_up_a_core);
        AddFloatingActionButton addElectron = Ui.findView(view, R.id.action_set_up_an_electron);

        addPhoton.setOnClickListener(v -> {
            addPhotonDevice();
            fabMenu.collapse();
        });
        addCore.setOnClickListener(v -> {
            addSparkCoreDevice();
            fabMenu.collapse();
        });
        addElectron.setOnClickListener(v -> {
            addElectronDevice();
            fabMenu.collapse();
        });

        refreshLayout = Ui.findView(view, R.id.refresh_layout);
        refreshLayout.setOnRefreshListener(this::refreshDevices);

        deviceSetupCompleteReceiver = new ParticleDeviceSetupLibrary.DeviceSetupCompleteReceiver() {
            @Override
            public void onSetupSuccess(String id) {
                log.d("Successfully set up " + id);
            }

            @Override
            public void onSetupFailure() {
                log.w("Device not set up.");
            }
        };
        deviceSetupCompleteReceiver.register(getActivity());

        getLoaderManager().initLoader(R.id.device_list_devices_loader_id, null, this);
        refreshLayout.setRefreshing(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            List<ParticleDevice> devices = adapter.getItems();
            subscribeToSystemEvents(devices, false);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        refreshDevices();
    }

    @Override
    public void onPause() {
        if (adapter != null) {
            List<ParticleDevice> devices = adapter.getItems();
            subscribeToSystemEvents(devices, true);
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
        refreshLayout.setRefreshing(false);
        fabMenu.collapse();
        reloadStateDelegate.reset();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        callbacks = dummyCallbacks;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        deviceSetupCompleteReceiver.unregister(getActivity());
    }

    @Override
    public Loader<DevicesLoadResult> onCreateLoader(int i, Bundle bundle) {
        return new DevicesLoader(getActivity());
    }

    @Override
    public void onLoadFinished(Loader<DevicesLoadResult> loader, DevicesLoadResult result) {
        refreshLayout.setRefreshing(false);

        ArrayList<ParticleDevice> devices = new ArrayList<>(result.devices);
        Collections.sort(devices, comparator);

        reloadStateDelegate.onDeviceLoadFinished(loader, result);

        adapter.clear();
        adapter.addAll(devices);
        bookends.notifyDataSetChanged();
        //subscribe to system updates
        subscribeToSystemEvents(devices, false);
    }

    private void subscribeToSystemEvents(List<ParticleDevice> devices, boolean revertSubscription) {
        for (ParticleDevice device : devices) {
            new AsyncTask<ParticleDevice, Void, Void>() {
                @Override
                protected Void doInBackground(ParticleDevice... particleDevices) {
                    try {
                        if (revertSubscription) {
                            for (Long id : subscribeIds) {
                                device.unsubscribeFromEvents(id);
                            }
                        } else {
                            subscribeIds.add(device.subscribeToEvents("spark/status", new ParticleEventHandler() {
                                @Override
                                public void onEventError(Exception e) {
                                    //ignore for now, events aren't vital
                                }

                                @Override
                                public void onEvent(String eventName, ParticleEvent particleEvent) {
                                    refreshDevices();
                                }
                            }));
                        }
                    } catch (IOException | ParticleCloudException ignore) {
                        //ignore for now, events aren't vital
                    }
                    return null;
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, device);
        }
    }

    @Override
    public void onLoaderReset(Loader<DevicesLoadResult> loader) {
        // no-op
    }

    private void onDeviceRowClicked(int position) {
        log.i("Clicked on item at position: #" + position);
        if (position >= bookends.getItemCount() || position == -1) {
            // we're at the header or footer view, do nothing.
            return;
        }

        // Notify the active callbacks interface (the activity, if the
        // fragment is attached to one) that an item has been selected.
        final ParticleDevice device = adapter.getItem(position);

        if (device.isFlashing()) {
            Toaster.s(getActivity(),
                    "Device is being flashed, please wait for the flashing process to end first");
        } else if (!device.isConnected() || !device.isRunningTinker()) {
            Activity activity = getActivity();
            activity.startActivity(InspectorActivity.buildIntent(activity, device));
        } else {
            callbacks.onDeviceSelected(device);
        }
    }

    public boolean onBackPressed() {
        if (fabMenu.isExpanded()) {
            fabMenu.collapse();
            return true;
        } else {
            return false;
        }
    }

    private void addPhotonDevice() {
        ParticleDeviceSetupLibrary.startDeviceSetup(getActivity());
    }

    private void addSparkCoreDevice() {
        String coreAppPkg = "io.spark.core.android";
        // Is the spark core app already installed?
        Intent intent = getActivity().getPackageManager().getLaunchIntentForPackage(coreAppPkg);
        if (intent == null) {
            // Nope.  Send the user to the store.
            intent = new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("market://details?id=" + coreAppPkg));
        }
        startActivity(intent);
    }

    private void addElectronDevice() {
        //        Intent intent = (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP)
//                ? new Intent(getActivity(), ElectronSetupActivity.class)
//                : new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.electron_setup_uri)));
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.electron_setup_uri)));
        startActivity(intent);
    }

    private void refreshDevices() {
        if (adapter != null) {
            List<ParticleDevice> devices = adapter.getItems();
            subscribeToSystemEvents(devices, true);
        }
        Loader<Object> loader = getLoaderManager().getLoader(R.id.device_list_devices_loader_id);
        loader.forceLoad();
    }


    static class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {

        static class ViewHolder extends RecyclerView.ViewHolder {

            final View topLevel;
            final TextView modelName;
            final AppCompatImageView productImage, statusIcon;
            final TextView deviceName;
            final TextView statusTextWithIcon;

            ViewHolder(View itemView) {
                super(itemView);
                topLevel = itemView;
                modelName = Ui.findView(itemView, R.id.product_model_name);
                productImage = Ui.findView(itemView, R.id.product_image);
                statusIcon = Ui.findView(itemView, R.id.online_status_image);
                deviceName = Ui.findView(itemView, R.id.product_name);
                statusTextWithIcon = Ui.findView(itemView, R.id.online_status);
            }
        }


        private final List<ParticleDevice> devices = list();
        private final FragmentActivity activity;
        private Drawable defaultBackground;

        DeviceListAdapter(FragmentActivity activity) {
            this.activity = activity;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // create a new view
            View v = LayoutInflater.from(parent.getContext()).inflate(
                    R.layout.row_device_list, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            final ParticleDevice device = devices.get(position);

            if (defaultBackground == null) {
                defaultBackground = holder.topLevel.getBackground();
            }
            holder.topLevel.setBackgroundResource(R.color.device_item_bg);

            switch (device.getDeviceType()) {
                case CORE:
                    holder.modelName.setText("Core");
                    holder.productImage.setImageResource(R.drawable.core_vector);
                    break;

                case ELECTRON:
                    holder.modelName.setText("Electron");
                    holder.productImage.setImageResource(R.drawable.electron_vector_small);
                    break;

                default:
                    holder.modelName.setText("Photon");
                    holder.productImage.setImageResource(R.drawable.photon_vector_small);
                    break;
            }

            Pair<String, Integer> statusTextAndColoredDot = getStatusTextAndColoredDot(device);
            holder.statusTextWithIcon.setText(statusTextAndColoredDot.first);
            holder.statusIcon.setImageResource(statusTextAndColoredDot.second);

            if (device.isConnected()) {
                Animation animFade = AnimationUtils.loadAnimation(activity, R.anim.fade_in_out);
                animFade.setStartOffset(position * 1000);
                holder.statusIcon.startAnimation(animFade);
            }

            Context ctx = holder.topLevel.getContext();
            String name = truthy(device.getName())
                    ? device.getName()
                    : ctx.getString(R.string.unnamed_device);
            holder.deviceName.setText(name);
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        void clear() {
            devices.clear();
            notifyDataSetChanged();
        }

        void addAll(List<ParticleDevice> toAdd) {
            devices.addAll(toAdd);
            notifyDataSetChanged();
        }

        ParticleDevice getItem(int position) {
            return devices.get(position);
        }

        List<ParticleDevice> getItems() {
            return devices;
        }

        private Pair<String, Integer> getStatusTextAndColoredDot(ParticleDevice device) {
            int dot;
            String msg;
            if (device.isFlashing()) {
                dot = R.drawable.device_flashing_dot;
                msg = "";

            } else if (device.isConnected()) {
                if (device.isRunningTinker()) {
                    dot = R.drawable.online_dot;
                    msg = "Tinker";

                } else {
                    dot = R.drawable.online_non_tinker_dot;
                    msg = "";
                }

            } else {
                dot = R.drawable.offline_dot;
                msg = "";
            }
            return Pair.create(msg, dot);
        }
    }


    private static Comparator<ParticleDevice> helpfulOrderDeviceComparator() {
        Comparator<ParticleDevice> deviceOnlineStatusComparator = (lhs, rhs) -> BooleanComparator.getTrueFirstComparator()
                .compare(lhs.isConnected(), rhs.isConnected());
        NullComparator<String> nullComparator = new NullComparator<>(false);
        Comparator<ParticleDevice> unnamedDevicesFirstComparator = (lhs, rhs) -> {
            String lhname = lhs.getName();
            String rhname = rhs.getName();
            return nullComparator.compare(lhname, rhname);
        };

        ComparatorChain<ParticleDevice> chain;
        chain = new ComparatorChain<>(deviceOnlineStatusComparator, false);
        chain.addComparator(unnamedDevicesFirstComparator, false);
        return chain;
    }


    private class ReloadStateDelegate {

        static final int MAX_RETRIES = 10;

        int retryCount = 0;

        void onDeviceLoadFinished(final Loader<DevicesLoadResult> loader, DevicesLoadResult result) {
            if (!result.isPartialResult) {
                reset();
                return;
            }

            retryCount++;
            if (retryCount > MAX_RETRIES) {
                // tried too many times, giving up. :(
                partialContentBar.setVisibility(View.INVISIBLE);
                return;
            }

            if (!isLoadingSnackbarVisible) {
                isLoadingSnackbarVisible = true;
                View view = getView();
                if (view != null) {
                    Snackbar.make(view, "Unable to load all devices", Snackbar.LENGTH_SHORT)
                            .addCallback(new Callback() {
                                @Override
                                public void onDismissed(Snackbar snackbar, int event) {
                                    super.onDismissed(snackbar, event);
                                    isLoadingSnackbarVisible = false;
                                }
                            }).show();
                }
            }

            partialContentBar.setVisibility(View.VISIBLE);
            ((DevicesLoader) loader).setUseLongTimeoutsOnNextLoad(true);
            // FIXME: is it OK to call forceLoad() in loader callbacks?  Test and be certain.
            EZ.runOnMainThread(() -> {
                if (isResumed()) {
                    loader.forceLoad();
                }
            });
        }

        void reset() {
            retryCount = 0;
            partialContentBar.setVisibility(View.INVISIBLE);
        }

    }

}
