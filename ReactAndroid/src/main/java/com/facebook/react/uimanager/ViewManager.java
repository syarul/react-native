/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.uimanager;

import javax.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import android.view.View;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.touch.CatalystInterceptingViewGroup;
import com.facebook.react.touch.JSResponderHandler;

/**
 * Class responsible for knowing how to create and update catalyst Views of a given type. It is also
 * responsible for creating and updating CSSNode subclasses used for calculating position and size
 * for the corresponding native view.
 */
public abstract class ViewManager<T extends View, C extends ReactShadowNode> {

  private static final Map<Class, Map<String, UIProp.Type>> CLASS_PROP_CACHE = new HashMap<>();

  public final void updateProperties(T viewToUpdate, CatalystStylesDiffMap props) {
    Map<String, ViewManagersPropertyCache.PropSetter> propSetters =
        ViewManagersPropertyCache.getNativePropSettersForViewManagerClass(getClass());
    ReadableMap propMap = props.mBackingMap;
    ReadableMapKeySetIterator iterator = propMap.keySetIterator();
    // TODO(krzysztof): Remove missingSetters code once all views are migrated to @ReactProp
    boolean missingSetters = false;
    while (iterator.hasNextKey()) {
      String key = iterator.nextKey();
      ViewManagersPropertyCache.PropSetter setter = propSetters.get(key);
      if (setter != null) {
        setter.updateViewProp(this, viewToUpdate, props);
      } else {
        missingSetters = true;
      }
    }
    if (missingSetters) {
      updateView(viewToUpdate, props);
    }
    onAfterUpdateTransaction(viewToUpdate);
  }

  /**
   * Creates a view and installs event emitters on it.
   */
  public final T createView(
      ThemedReactContext reactContext,
      JSResponderHandler jsResponderHandler) {
    T view = createViewInstance(reactContext);
    addEventEmitters(reactContext, view);
    if (view instanceof CatalystInterceptingViewGroup) {
      ((CatalystInterceptingViewGroup) view).setOnInterceptTouchEventListener(jsResponderHandler);
    }
    return view;
  }

  /**
   * @return the name of this view manager. This will be the name used to reference this view
   * manager from JavaScript in createReactNativeComponentClass.
   */
  public abstract String getName();

  /**
   * This method should return a subclass of {@link ReactShadowNode} which will be then used for
   * measuring position and size of the view. In mose of the cases this should just return an
   * instance of {@link ReactShadowNode}
   */
  public abstract C createShadowNodeInstance();

  /**
   * This method should return {@link Class} instance that represent type of shadow node that this
   * manager will return from {@link #createShadowNodeInstance}.
   *
   * This method will be used in the bridge initialization phase to collect properties exposed using
   * {@link ReactProp} (or {@link ReactPropGroup}) annotation from the {@link ReactShadowNode}
   * subclass specific for native view this manager provides.
   *
   * @return {@link Class} object that represents type of shadow node used by this view manager.
   */
  public abstract Class<? extends C> getShadowNodeClass();

  /**
   * Subclasses should return a new View instance of the proper type.
   * @param reactContext
   */
  protected abstract T createViewInstance(ThemedReactContext reactContext);

  /**
   * Called when view is detached from view hierarchy and allows for some additional cleanup by
   * the {@link ViewManager} subclass.
   */
  public void onDropViewInstance(ThemedReactContext reactContext, T view) {
  }

  /**
   * Subclasses can override this method to install custom event emitters on the given View. You
   * might want to override this method if your view needs to emit events besides basic touch events
   * to JS (e.g. scroll events).
   */
  protected void addEventEmitters(ThemedReactContext reactContext, T view) {
  }

  /**
   * Subclass should use this method to populate native view with updated style properties. In case
   * when a certain property is present in {@param props} map but the value is null, this property
   * should be reset to the default value
   *
   * TODO(krzysztof) This method should be replaced by updateShadowNode and removed completely after
   * all view managers adapt @ReactProp
   */
  @Deprecated
  protected void updateView(T root, CatalystStylesDiffMap props) {
  }

  /**
   * Callback that will be triggered after all properties are updated in current update transaction
   * (all @ReactProp handlers for properties updated in current transaction have been called). If
   * you want to override this method you should call super.onAfterUpdateTransaction from it as
   * the parent class of the ViewManager may rely on callback being executed.
   */
  protected void onAfterUpdateTransaction(T view) {
  }

  /**
   * Subclasses can implement this method to receive an optional extra data enqueued from the
   * corresponding instance of {@link ReactShadowNode} in
   * {@link ReactShadowNode#onCollectExtraUpdates}.
   *
   * Since css layout step and ui updates can be executed in separate thread apart of setting
   * x/y/width/height this is the recommended and thread-safe way of passing extra data from css
   * node to the native view counterpart.
   *
   * TODO(7247021): Replace updateExtraData with generic update props mechanism after D2086999
   */
  public abstract void updateExtraData(T root, Object extraData);

  /**
   * Subclasses may use this method to receive events/commands directly from JS through the
   * {@link UIManager}. Good example of such a command would be {@code scrollTo} request with
   * coordinates for a {@link ScrollView} or {@code goBack} request for a {@link WebView} instance.
   *
   * @param root View instance that should receive the command
   * @param commandId code of the command
   * @param args optional arguments for the command
   */
  public void receiveCommand(T root, int commandId, @Nullable ReadableArray args) {
  }

  /**
   * Subclasses of {@link ViewManager} that expect to receive commands through
   * {@link UIManagerModule#dispatchViewManagerCommand} should override this method returning the
   * map between names of the commands and IDs that are then used in {@link #receiveCommand} method
   * whenever the command is dispatched for this particular {@link ViewManager}.
   *
   * As an example we may consider {@link ReactWebViewManager} that expose the following commands:
   * goBack, goForward, reload. In this case the map returned from {@link #getCommandsMap} from
   * {@link ReactWebViewManager} will look as follows:
   * {
   *   "goBack": 1,
   *   "goForward": 2,
   *   "reload": 3,
   * }
   *
   * Now assuming that "reload" command is dispatched through {@link UIManagerModule} we trigger
   * {@link ReactWebViewManager#receiveCommand} passing "3" as {@code commandId} argument.
   *
   * @return map of string to int mapping of the expected commands
   */
  public @Nullable Map<String, Integer> getCommandsMap() {
    return null;
  }

  /**
   * Returns a map of config data passed to JS that defines eligible events that can be placed on
   * native views. This should return bubbling directly-dispatched event types and specify what
   * names should be used to subscribe to either form (bubbling/capturing).
   *
   * Returned map should be of the form:
   * {
   *   "onTwirl": {
   *     "phasedRegistrationNames": {
   *       "bubbled": "onTwirl",
   *       "captured": "onTwirlCaptured"
   *     }
   *   }
   * }
   */
  public @Nullable Map<String, Object> getExportedCustomBubblingEventTypeConstants() {
    return null;
  }

  /**
   * Returns a map of config data passed to JS that defines eligible events that can be placed on
   * native views. This should return non-bubbling directly-dispatched event types.
   *
   * Returned map should be of the form:
   * {
   *   "onTwirl": {
   *     "registrationName": "onTwirl"
   *   }
   * }
   */
  public @Nullable Map<String, Object> getExportedCustomDirectEventTypeConstants() {
    return null;
  }

  /**
   * Returns a map of view-specific constants that are injected to JavaScript. These constants are
   * made accessible via UIManager.<ViewName>.Constants.
   */
  public @Nullable Map<String, Object> getExportedViewConstants() {
    return null;
  }

  public Map<String, String> getNativeProps() {
    // TODO(krzysztof): This method will just delegate to ViewManagersPropertyRegistry once
    // refactoring is finished
    Class cls = getClass();
    Map<String, String> nativeProps =
        ViewManagersPropertyCache.getNativePropsForView(cls, getShadowNodeClass());
    while (cls.getSuperclass() != null) {
      Map<String, UIProp.Type> props = getNativePropsForClass(cls);
      for (Map.Entry<String, UIProp.Type> entry : props.entrySet()) {
        nativeProps.put(entry.getKey(), entry.getValue().toString());
      }
      cls = cls.getSuperclass();
    }
    return nativeProps;
  }

  private Map<String, UIProp.Type> getNativePropsForClass(Class cls) {
    // TODO(krzysztof): Blow up this method once refactoring is finished
    Map<String, UIProp.Type> props = CLASS_PROP_CACHE.get(cls);
    if (props != null) {
      return props;
    }
    props = new HashMap<>();
    for (Field f : cls.getDeclaredFields()) {
      UIProp annotation = f.getAnnotation(UIProp.class);
      if (annotation != null) {
        UIProp.Type type = annotation.value();
        try {
          String name = (String) f.get(this);
          props.put(name, type);
        } catch (IllegalAccessException e) {
          throw new RuntimeException(
              "UIProp " + cls.getName() + "." + f.getName() + " must be public.");
        }
      }
    }
    CLASS_PROP_CACHE.put(cls, props);
    return props;
  }
}
