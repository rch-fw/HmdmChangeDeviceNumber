import hashlib
import requests

# --- Configuration ---
BASE_URL = "https://mdm-dev.mnyrnd.com"
USERNAME = "user"
PASSWORD = "pwd"

CURRENT_DEVICE_NUMBER = "devname_old"
NEW_DEVICE_NUMBER = "devname_new"


def get_md5_uppercase(password: str) -> str:
    """Headwind MDM requires the MD5 hash of the password in uppercase."""
    return hashlib.md5(password.encode("utf-8")).hexdigest().upper()


def authenticate(base_url: str, username: str, password: str) -> str:
    """Logs into Headwind MDM and returns the JWT token."""
    url = f"{base_url.rstrip('/')}/rest/public/jwt/login"
    payload = {
        "login": username,
        "password": get_md5_uppercase(password)
    }
    
    response = requests.post(url, json=payload, timeout=15)
    response.raise_for_status()
    
    data = response.json()
    
    # 1. Check if token is at the root level: {"id_token": "..."}
    if "id_token" in data:
        return data["id_token"]
        
    # 2. Check if token is nested: {"status": "OK", "data": {"id_token": "..."}}
    if data.get("status") == "OK" and "id_token" in data.get("data", {}):
        return data["data"]["id_token"]
    
    raise ValueError(f"Authentication failed: {data}")


def rename_device(base_url: str, token: str, old_number: str, new_number: str) -> int:
    """Renames the device; the server also sends the configUpdated push. Returns the device id."""
    url = f"{base_url.rstrip('/')}/rest/private/devices/rename"
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    body = {"number": old_number, "newNumber": new_number}
    r = requests.post(url, json=body, headers=headers, timeout=15)
    r.raise_for_status()

    data = r.json()
    # Headwind reports application errors with HTTP 200: {"status": "ERROR", "message": "error.xxx"}
    if data.get("status") != "OK":
        raise ValueError(f"Rename failed: {data.get('message', data)}")
    return data["data"]


def main():
    try:
        print("Authenticating...")
        token = authenticate(BASE_URL, USERNAME, PASSWORD)
        print("Authentication successful.")

        print(f"Renaming device '{CURRENT_DEVICE_NUMBER}' to '{NEW_DEVICE_NUMBER}'...")
        device_id = rename_device(BASE_URL, token, CURRENT_DEVICE_NUMBER, NEW_DEVICE_NUMBER)
        print(f"Device (ID: {device_id}) renamed and notified successfully!")

    except requests.exceptions.HTTPError as http_err:
        print(f"HTTP error occurred: {http_err} - {http_err.response.text}")
    except Exception as err:
        print(f"An error occurred: {err}")

if __name__ == "__main__":
    main()
