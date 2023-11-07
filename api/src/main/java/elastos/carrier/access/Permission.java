package elastos.carrier.access;

import java.util.Map;

public interface Permission {
	public enum Access {
		Allow, Deny;

		public static Access of(String value) {
			switch (value.toLowerCase()) {
			case "allow":
				return Allow;

			case "deny":
				return Deny;

			default:
				throw new IllegalArgumentException("Unknown: " + value);
			}
		}
	}

	public String getTargetServiceId();

	public Access getAccess();

	default public boolean isAllow() {
		return getAccess() == Access.Allow;
	}

	default public boolean isDeny() {
		return getAccess() == Access.Deny;
	}

	public Map<String, Object> getProperties();
}
