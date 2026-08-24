"""
从 Reqable saved 请求文件提取响应 body + 解密
用法: python extract_and_decrypt.py <saved_request_file>
"""
import base64
import json
import re
import sys

from Crypto.Cipher import AES, PKCS1_v1_5
from Crypto.PublicKey import RSA
from Crypto.Util.Padding import unpad

def load_priv_pem():
    with open("wmmt_keys.json", "r") as f:
        p = json.load(f)["privateKey"]
    # 动态私钥是裸 base64 DER，包装成 PEM(PKCS8)
    return "-----BEGIN PRIVATE KEY-----\n" + p + "\n-----END PRIVATE KEY-----"

def rsa_decrypt_aes_key(enc_key_b64, priv_pem):
    key = RSA.import_key(priv_pem)
    enc = base64.b64decode(enc_key_b64)
    d = PKCS1_v1_5.new(key).decrypt(enc, None)
    return base64.b64decode(d).decode("utf-8")

def aes_ecb_decrypt(cipher_b64, key_str):
    data = base64.b64decode(cipher_b64)
    cipher = AES.new(key_str.encode("utf-8"), AES.MODE_ECB)
    return unpad(cipher.decrypt(data), AES.block_size)

if __name__ == "__main__":
    fpath = sys.argv[1]
    priv = load_priv_pem()
    raw = open(fpath, "r", encoding="utf-8", errors="replace").read()
    # 提取响应 encrypt-key 头（可能在 response 段，也可能请求段——取 response 附近的）
    resp_idx = raw.find('"response"')
    if resp_idx < 0:
        resp_idx = 0
    resp_raw = raw[resp_idx:]
    m_ek = re.search(r'"encrypt-key","value":"([^"]*)"', resp_raw)
    m_body = re.search(r'"body":\{"text":"((?:[^"\\]|\\.)*)"\,"mime"', resp_raw, re.S)
    # fallback: 若 response 段无 encrypt-key，尝试整文件第一个
    if not m_ek:
        m_ek = re.search(r'"encrypt-key","value":"([^"]*)"', raw)
    if not m_body:
        m_body = re.search(r'"body":\{"text":"((?:[^"\\]|\\.)*)"', raw)
    if not m_ek or not m_body:
        print("未找到 encrypt-key 或 body")
        # 兜底：打印 response 附近原始片段
        idx = raw.find('"response"')
        print("resp 片段:", raw[idx:idx+500] if idx>=0 else "无")
        sys.exit(1)
    ek = m_ek.group(1)
    body_json = m_body.group(1)  # JSON 中已转义，含 \n 等
    # body_json 是 JSON 字符串里的转义形，需要 JSON.loads 还原成真实字符串
    body = json.loads('"' + body_json + '"')
    print("encrypt-key 前缀:", ek[:30], "...")
    print("body 长度:", len(body))
    aes_key = rsa_decrypt_aes_key(ek, priv)
    print("AES key:", repr(aes_key))
    try:
        pt = aes_ecb_decrypt(body, aes_key)
        try:
            text = pt.decode("utf-8")
        except UnicodeDecodeError:
            text = "二进制/压缩(hex): " + pt.hex()
        print("=== 解密明文(前3000) ===")
        print(text[:3000])
    except Exception as e:
        print("解密失败:", e)