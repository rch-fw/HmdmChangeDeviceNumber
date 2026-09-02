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


def fetch_device(base_url: str, token: str, device_number: str) -> dict:
    """Retrieves the complete device object by its current device number."""
    url = f"{base_url.rstrip('/')}/rest/private/devices/number/{device_number}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    
    response = requests.get(url, headers=headers, timeout=15)
    response.raise_for_status()
    
    data = response.json()
    
    # If the response wraps data inside a "data" field
    if isinstance(data, dict) and "data" in data and data.get("status") == "OK":
        return data["data"]
        
    # If the response directly returns the device object (e.g., contains "id" or "number")
    if isinstance(data, dict) and ("id" in data or "number" in data):
        return data
        
    raise ValueError(f"Device '{device_number}' not found or unexpected response structure: {data}")


import json

def update_device_number(base_url: str, token: str, device_obj: dict, old_number: str, new_number: str) -> dict:
    """Updates the device object with the new device number via PUT request."""
    url = f"{base_url.rstrip('/')}/rest/private/devices"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    
    # 1. Modify the device number
    device_obj["number"] = new_number
    device_obj["oldNumber"] = old_number
    
    # 2. Convert 'info' back to a JSON string if it was returned as a dict
    if isinstance(device_obj.get("info"), dict):
        device_obj["info"] = json.dumps(device_obj["info"])
        
    response = requests.put(url, json=device_obj, headers=headers, timeout=15)
    response.raise_for_status()
    
    return response.json()
    
    
def notify_config_updated(base_url: str, token: str, device_number: str) -> dict:
    url = f"{base_url.rstrip('/')}/rest/private/push"
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    body = {
        "messageType": "configUpdated",       # PushMessage.TYPE_CONFIG_UPDATED
        "deviceNumbers": [device_number],     # use the NEW number (lookup is by devices.number)
    }
    r = requests.post(url, json=body, headers=headers, timeout=15)
    r.raise_for_status()
    return r.json()


def main():
    try:
        print("Authenticating...")
        token = authenticate(BASE_URL, USERNAME, PASSWORD)
        print("Authentication successful.")

        print(f"Fetching device data for '{CURRENT_DEVICE_NUMBER}'...")
        device_data = fetch_device(BASE_URL, token, CURRENT_DEVICE_NUMBER)
        
        # 1. Store the internal integer ID (e.g., 3)
        device_id = device_data.get("id")
        print(f"Found Device (ID: {device_id})")

        print(f"Updating device number to '{NEW_DEVICE_NUMBER}'...")
        update_result = update_device_number(BASE_URL, token, device_data, CURRENT_DEVICE_NUMBER, NEW_DEVICE_NUMBER)

        if update_result.get("status") == "OK" or "id" in update_result:
            print("Device number updated on server successfully!")
        else:
            print(f"Update returned status: {update_result}")

        notify_config_updated(BASE_URL, token,NEW_DEVICE_NUMBER);

    except requests.exceptions.HTTPError as http_err:
        print(f"HTTP error occurred: {http_err} - {http_err.response.text}")
    except Exception as err:
        print(f"An error occurred: {err}")

if __name__ == "__main__":
    main()
