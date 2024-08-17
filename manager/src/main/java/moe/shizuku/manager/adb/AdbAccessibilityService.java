package moe.shizuku.manager.adb;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public class AdbAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo nodeInfo = event.getSource();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nodeInfo == null) {
            nodeInfo = getRootInActiveWindow();
        }
        if (nodeInfo == null) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null && event.getClassName() != null) {
            if (event.getPackageName().toString().equals("com.android.systemui")
                    && event.getClassName().toString().equals(("android.app.Dialog"))) {
                AccessibilityNodeInfo permissionWindowNodeInfo = event.getSource();
                List<AccessibilityNodeInfo> nodeInfoList = new ArrayList<>(permissionWindowNodeInfo.findAccessibilityNodeInfosByText("allow"));
                for (AccessibilityNodeInfo node : nodeInfoList) {
                    if (node.isClickable()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Do nothing
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        // perform auto-click action
    }
}
