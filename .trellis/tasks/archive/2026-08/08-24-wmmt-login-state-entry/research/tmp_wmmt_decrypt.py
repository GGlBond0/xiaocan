"""
歪麦抓包解密工具（复刻 WmmtHttp 加解密逻辑）
1. 解 newServiceConfig 的 data 字段（LEGACY_AES_KEY 固定）拿动态 RSA 私钥/公钥
2. 用动态私钥解业务响应的 encrypt-key -> AES key, 再解 body -> 明文
用法: python wmmt_decrypt.py
"""
import base64
import json
import sys

from Crypto.Cipher import AES, PKCS1_v1_5
from Crypto.PublicKey import RSA
from Crypto.Util.Padding import unpad

LEGACY_AES_KEY = b"jnd674751fh6fkgu"   # newServiceConfig 用
AES_TRANS = "AES/ECB/PKCS5Padding"

def aes_ecb_decrypt(cipher_b64: str, key_bytes: bytes) -> str:
    data = base64.b64decode(cipher_b64)
    cipher = AES.new(key_bytes, AES.MODE_ECB)
    return unpad(cipher.decrypt(data), AES.block_size).decode("utf-8")

def rsa_priv_decrypt(enc_b64: str, priv_pem: str) -> bytes:
    key = RSA.import_key(priv_pem.replace("-----BEGIN PRIVATE KEY-----", "")
                         .replace("-----END PRIVATE KEY-----", "").replace("\\s", "").encode() if False else priv_pem)
    enc = base64.b64decode(enc_b64)
    cipher = PKCS1_v1_5.new(key)
    sentinel = None
    # 返回原始字节（调用方再 b64 decode 一次，对齐 WmmtHttp：rsaDecrypt -> base64 decode -> aes key 字符串）
    return cipher.decrypt(enc, sentinel)

# ========== 从 newServiceConfig 解出动态 key ==========
# 填入 id=3 响应的 data 字段（AES LEGACY 密文）
NEW_CONFIG_DATA = "IhCe8O14Y+XdL0p1IGz8HBX95mjeqotWRm2a3NkGv/rSiBgwiofDxXMqsZyt70OXbn7tTv9SBGUjqAm6TfJIX4zLicYXyZE6mSuRP9D4Yenf7RdO1fHiZpuaf6ilIgIpm9CM8bVl9VzdRzr1APG77BANolu9/oDQW7pDoyegvvSt0h8MZUgz5kNLTUvXOFfoeDhfqXcIfZNQF4fALJMoftICpYcoeVLVIbxO88b3LI9OhJubeAUaJsJsMaLJxwwyjvT+dGM92NzaOrtzpHnCn5K+8tmr/Zr+GwrpJrUXTQn5Q/0VOc7gNNYrZePu+i55ZIR2XQvlc5bm2X9NwzjORLu2y02d0Vx9A1yFOHfP/KMpYpRC9nJrpavcce9kx0EOw/sTIIuYTs5Y58foDyTHZXolffxY+kT0RhgAMAG7ujPP6VAZCSxjFIzlS5Ti1HkMa7tZOWF6HGG8/7kDRePo+D9SmQAmHKfIIgIgOxlNI4ZL80UVNXEYJgeGpsaJSGiQDi0Ycvq0nUKFOqa8XxdYYnU68yPKya4wiDlVu1jm2ntFoIvsu74Gzl59d8LJaa9W6+ka0lCokdpIn5+qnY8HgQYsM1hrgLh2EImgAn/IZTz2i6hnVIzz8jpvb1X4Woa6iJeK0AJG344Jnlqy2QCtycusAArTdECJoDiE/CYXWJQTo6XmZUQlkOiiHegP47CwTDf5uE+Lfx4waZBenFEbrO8bYR8CE7ycaiDrWQ3g68qMGxjmvKVMPMs3qCeLAgy9vMI2G2gN/Lc4WBmCgTt8fIuVU3EpXs4dYQbjEz9pTSH7PV79BpcJXu4CBdSocrSWZAMpI880WYmHM/BT2pqPhdoLCj/a2iWXGRSm1RoagcNObAuGii4x8UpPh3kIgkLvDglHTd4HdwBmt/haaKL6ISEZHnV3Hc4U74C35Op9UE5YHQiAWmhU9AgmZlyut9qvzHRuY4722KipJYJHfBlFxo1ytGp68pPgZM/5vBVh3U3Z+wgpBeP69Qxacn57TeLBNmNRYrg1zob+RD5VIprVTNOt9HJVnKDwINS/ARkcn6YVKAuCftyj844SNgbUugyndtA+qIfRiDcEeAish1dqrbciayX71kjxt6A9V1bhuouxlnlv3ZnlZBJQb/ovoRPzIX5SHo+ni3e87FadqSYrnd7bglb1Pck/D1BaAKsuhnktR8zdxAtPdhIl9SssSvLjV0j3vxKg7vgI+IfxpJa9PW2uDIaLLyGCzYSvYTbQAu/Q8ptwXLGd3iHW3omBOJmJiG350T3AQ/d82ejdmTQZISRJUNn6R6jD7rzlQ4/oaYXSw7hg0OMHe1oyJCeMGzqc7zwZ0zt/pfg/UR6DjPI5Ps0HAPXmMwsgp0s6z+RsoCKkMv3Ux1bQrLiJ9L8JRvD6+AOL2H2I9QWXi5Az43zpOGWdBg+7eXjFmDRRtwlxKr55XvBFvUjYuDG5ccAB/DSRmboj/To5JwzgpL89qxMYKOATAzJd2v+9HfT67pqESGsM80cqzYT0cbgXJQUdOLD+ZFMi244KBxoQf/Sz7ZYj5tO22M/XE1eyn1Sd/WhhUsUGv3lCNfIwR8V7oWhJFAnsAI5S6DX4+EbqaSZLCZTkAUHBR6tp3lR4jHYQqpD8G5lnIsN8sBYuhLAmqd9FC3aEjyTxF3ldN7t/O6HJmb15aoqGwbbKV8JmECUTZVt0/KXRIL3M17RDZ7Jc7qD8xGItGpdUOyrQ99bwj4jvCKd5JiUutm13V6Vu4RNDp2stfw2njXsjP0ugkKWOfIB3zb7C2UXXp4meUmk2IVQYn24N9K5ttn5Ac15CXVIkJvQLTpaoMFLo1QKwDOANGPJBGdT4Bk95qPkiEXGgi5YKgQIvciq4vt0zqVe/7cNYewKuJmQ="

def main():
    print("=== Step1: 解 newServiceConfig data (LEGACY_AES_KEY) ===")
    dec = aes_ecb_decrypt(NEW_CONFIG_DATA, LEGACY_AES_KEY)
    cfg = json.loads(dec)
    private_key = cfg.get("privateKey")
    public_key = cfg.get("publicKey")
    h5_public = cfg.get("h5PublicKey")
    print("privateKey 前缀:", (private_key or "")[:40], "...")
    print("publicKey 前缀:", (public_key or "")[:40], "...")
    print("h5PublicKey 前缀:", (h5_public or "")[:40], "...")
    # 保存密钥备用
    with open("wmmt_keys.json", "w") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print("已保存 wmmt_keys.json")

if __name__ == "__main__":
    main()