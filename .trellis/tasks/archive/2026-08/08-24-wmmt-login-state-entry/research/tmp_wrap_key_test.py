# 修正：wrap privateKey 成 PEM 再导入（对齐 WmmtHttp.loadPrivateKey）
import base64, json
from Crypto.PublicKey import RSA

cfg = json.load(open("wmmt_keys.json", "r"))
priv_b64 = cfg["privateKey"]
# 包成 PEM（PKCS8）
pem = "-----BEGIN PRIVATE KEY-----\n" + priv_b64 + "\n-----END PRIVATE KEY-----"
print("pem 前缀:", repr(pem[:30]))
try:
    key = RSA.import_key(pem)
    print("PEM 导入 OK, 位数:", key.size_in_bits())
    # 测试用公钥加密一个已知 AES key 再解,验证可用
    from Crypto.Cipher import AES, PKCS1_v1_5
    import base64 as b64
    # 模拟：RSA 加密一个 aes key base64
    aes_b64 = b64.b64encode(b"someaeskey1234567890abcdef").decode()
    from Crypto.Cipher import PKCS1_v1_5 as P15
    # 用 publicKey 加密(模拟服务端给我们的 encrypt-key)
    pub_b64 = cfg["publicKey"]
    pubpem = "-----BEGIN PUBLIC KEY-----\n" + pub_b64 + "\n-----END PUBLIC KEY-----"
    pub = RSA.import_key(pubpem)
    enc = P15.new(pub).encrypt(aes_b64.encode())
    # 现在用私钥解
    d = P15.new(key).decrypt(enc, None)
    print("RSA 私钥解密回:", d.decode())
    print("=== 完整加解密链路验证成功 === 解密链可用")
except Exception as e:
    import traceback
    traceback.print_exc()