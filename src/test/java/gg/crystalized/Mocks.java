package gg.crystalized;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

public final class Mocks {
	private Mocks() {
	}

	public static <T> T mock(Class<T> iface) {
		return mock(iface, Map.of());
	}

	@SuppressWarnings("unchecked")
	public static <T> T mock(Class<T> iface, Map<String, Object> stubs) {
		return (T) Proxy.newProxyInstance(Mocks.class.getClassLoader(), new Class<?>[] { iface },
				(proxy, method, args) -> {
					if (method.getName().equals("equals")) {
						return proxy == args[0];
					}
					if (method.getName().equals("hashCode")) {
						return System.identityHashCode(proxy);
					}
					if (method.getName().equals("toString")) {
						return "Mock(" + iface.getSimpleName() + ")";
					}
					if (stubs.containsKey(method.getName())) {
						return stubs.get(method.getName());
					}
					return defaultValue(method.getReturnType(), iface);
				});
	}

	public static <T> T recording(Class<T> iface, List<Object[]> calls, Map<String, Object> stubs) {
		return (T) Proxy.newProxyInstance(Mocks.class.getClassLoader(), new Class<?>[] { iface },
				(proxy, method, args) -> {
					if (method.getName().equals("equals")) {
						return proxy == args[0];
					}
					if (method.getName().equals("hashCode")) {
						return System.identityHashCode(proxy);
					}
					if (method.getName().equals("toString")) {
						return "Mock(" + iface.getSimpleName() + ")";
					}
					calls.add(new Object[] { method.getName(), args });
					if (stubs.containsKey(method.getName())) {
						return stubs.get(method.getName());
					}
					return defaultValue(method.getReturnType(), iface);
				});
	}

	private static Object defaultValue(Class<?> rt, Class<?> iface) {
		if (!rt.isPrimitive()) {
			if (rt == String.class) {
				return "";
			}
			if (rt.isInterface()) {
				return mock(rt);
			}
			return null;
		}
		if (rt == boolean.class) {
			return false;
		}
		if (rt == void.class) {
			return null;
		}
		if (rt == double.class) {
			return 0.0d;
		}
		if (rt == float.class) {
			return 0.0f;
		}
		if (rt == char.class) {
			return '\0';
		}
		return 0;
	}
}
