"""
歪麦响应解密（复刻 WmmtHttp.decryptRes）：
  decrypt-key(RSA 私钥) -> AES key -> AES 解 body -> 明文(brotli解压可选)
读取已保存的 wmmt_keys.json 拿动态私钥。
"""
import base64
import json
import sys

from Crypto.Cipher import AES, PKCS1_v1_5
from Crypto.PublicKey import RSA
from Crypto.Util.Padding import unpad

def load_priv_pem():
    with open("wmmt_keys.json", "r") as f:
        cfg = json.load(f)
    return cfg["privateKey"]

def rsa_decrypt_aes_key(enc_key_b64, priv_pem):
    key = RSA.import_key(priv_pem)
    enc = base64.b64decode(enc_key_b64)
    d = PKCS1_v1_5.new(key).decrypt(enc, None)
    # WmmtHttp.rsaDecryptEncryptKey: rsa原文 -> base64 decode -> aes key 字符串(utf8)
    return base64.b64decode(d).decode("utf-8")

def aes_ecb_decrypt(cipher_b64, key_str):
    data = base64.b64decode(cipher_b64)
    cipher = AES.new(key_str.encode("utf-8"), AES.MODE_ECB)
    return unpad(cipher.decrypt(data), AES.block_size)

def decrypt_resp(enc_key_b64, body_b64, priv_pem):
    aes_key = rsa_decrypt_aes_key(enc_key_b64, priv_pem)
    print("解出 AES key:", repr(aes_key)[:20], "...")
    pt = aes_ecb_decrypt(body_b64, aes_key)
    # 尝试 utf-8，失败可能是 br 压缩
    try:
        return pt.decode("utf-8")
    except UnicodeDecodeError:
        return pt  # 可能是 brotli/其他二进制

if __name__ == "__main__":
    priv = load_priv_pem()
    if len(sys.argv) >= 3:
        ek = sys.argv[1]
        body = sys.argv[2]
    else:
        # 默认用 getShopList(id=26) 响应
        ek = "t/8vz7PvySEE5bi+QKeI4p+MN9UelLJbo3qAqnSKPI4zEy6EgLgdsbYJSe3+D+Uvx84a2uc+/svsUiWXs+lV3asg3HyDY/iocJxwrHhdZjPHD0fNHojqNthKrePqGSzzR1XxWMtYrCOuqYqYsosFcPfX7Bp/hIwqEQ3JOJiC0hw="
        # 从 saved 请求文件读 body（简化：直接传）
        print("需要传入 body。用法: python wmmt_decrypt_resp.py <encrypt_key> <body>")
        sys.exit(1)
    out = decrypt_resp(ek, body, priv)
    if isinstance(out, bytes):
        print("输出二进制，可能是压缩；提示检查 Content-Encoding")
        print("前200字(hex):", out[:100].hex())
    else:
        print("=== 明文响应 ===")
        print(out[:3000])