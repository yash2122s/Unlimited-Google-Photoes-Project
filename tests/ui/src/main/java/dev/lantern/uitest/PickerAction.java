package dev.lantern.uitest;

import android.app.Instrumentation;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;


/** Exercise the same accessible actions as a user. Runs only in the separately installed QA APK. */
public final class PickerAction extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle b) { super.onCreate(b); args=b; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            AccessibilityNodeInfo root=null;
            android.app.UiAutomation automation=getUiAutomation();
            for(int attempt=0; attempt<25 && root==null; attempt++) {
                android.os.SystemClock.sleep(200);
                root=automation.getRootInActiveWindow();
            }
            if(root==null) throw new IllegalStateException("No active window");
            String pkg=String.valueOf(root.getPackageName());
            if(!(pkg.equals("dev.lantern.android") || pkg.equals("com.android.documentsui") || pkg.equals("com.google.android.documentsui")))
                throw new IllegalStateException("Outside Lantern or system picker");
            String label=args.getString("label","");
            boolean scroll="scroll".equals(args.getString("action"));
            ArrayDeque<AccessibilityNodeInfo> todo=new ArrayDeque<>(); todo.add(root);
            while(!todo.isEmpty()) {
                AccessibilityNodeInfo n=todo.removeFirst();
                if(scroll && n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    result.putString("result","Scrolled"); finish(0,result); return;
                }
                if(!scroll && !label.isEmpty() && (label.equalsIgnoreCase(String.valueOf(n.getText())) || label.equalsIgnoreCase(String.valueOf(n.getContentDescription())))) {
                    while(n!=null) {
                        if(n.isEnabled() && n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            result.putString("result","Clicked "+label); finish(0,result); return;
                        }
                        n=n.getParent();
                    }
                    continue;
                }
                for(int i=0; i<n.getChildCount(); i++) { AccessibilityNodeInfo child=n.getChild(i); if(child!=null) todo.add(child); }
            }
            throw new IllegalStateException("Control unavailable");
        } catch(Exception e) { result.putString("failure",e.toString()); finish(1,result); }
    }
}


