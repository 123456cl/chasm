# -*- coding: utf-8 -*-
"""
生成 probe-diff 的**第二份** fixture：c-real.jsonl / d-port.jsonl。

它专门用来验证 probe-diff/2 的三个新能力（fixture1 保持原样，继续当 54 类覆盖基准）：

  * --pair-by：真机侧 variant/material_page 恒为 -1，端口侧是真值，
    所以按 state 全签名永远配不上（会全部降级）。这里让"两侧 state 键名集合相同、值不同"，
    于是默认模式只能靠降级猜；而 --pair-by gui,page,modules 能精确配上。
    同时 C-R1 与 C-R3 在 page+modules 上相同 → 显式配对键会碰撞（pair_key_collision）。

  * --slots-scope：真机侧 slots[] 把 4 个玩家背包槽也算进来（role 同样是 material、ci>=9），
    且两侧**数组下标顺序不同**。
      real-all      = 按数组下标配对 → 错位 + 背包槽全报缺失（噪声）
      role（默认）  = 按 (role,containerIndex) 配对 → 容器槽对上了，但背包槽仍在比（残留噪声）
      storage-only  = 再加 containerIndex < 9 → 背包槽被排除（干净）

  * --focus / --ignore：节点 key 跨多个前缀族（frame: / name: / attack_damage: / (structural r) /
    grow: / gname: / tab: / extra: / (structural craft_bg)），便于验证族汇总与下钻。

依赖 make_fixtures.py 里的 N/slot/ia/line 助手（同目录），不重复造轮子。
"""
import json
import os
import sys

# 别为 import 生成 __pycache__（交付目录保持干净）
sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_fixtures import N, slot, ia, line, MOD, TEX_A, TEX_B

OUT = os.path.dirname(os.path.abspath(__file__))

# 两侧都用同一套 5 个 state 键 —— 键名集合相同、值不同（真机侧 variant/material_page 无对应维度）
KEYS_A = ["modules", "group", "special", "variant", "material_page"]
DECL = {"modules": [-1, -1, 4], "group": [-1, -1, 99], "special": [-1, -1, 9],
        "variant": [-1, -1, 9], "material_page": [-1, -1, 9]}

INV = [9, 18, 27, 10]   # 真机侧多出来的玩家背包槽 containerIndex


def real_slots():
    """真机侧风格：容器槽 4 个 + 玩家背包槽 4 个（role 全是 material）。"""
    out = [
        slot(0, 0, 152, 58, True, False, MOD, 1,
             sem={"role": "target", "targetItem": MOD, "containerClass": "net.minecraft.world.SimpleContainer"}),
        slot(1, 1, 194, 108, True, True, None, 0,
             sem={"role": "material", "materialIndex": "0", "containerClass": "net.minecraft.world.SimpleContainer"}),
        slot(2, 2, 222, 108, True, True, None, 0,
             sem={"role": "material", "materialIndex": "1", "containerClass": "net.minecraft.world.SimpleContainer"}),
        slot(3, 3, 250, 108, True, True, None, 0,
             sem={"role": "material", "materialIndex": "2", "containerClass": "net.minecraft.world.SimpleContainer"}),
    ]
    for k, ci in enumerate(INV):
        out.append(slot(4 + k, ci, 84 + 17 * k, 166, True, True, None, 0,
                        sem={"role": "material", "materialIndex": str(3 + k),
                             "containerClass": "net.minecraft.world.SimpleContainer"}))
    return out


def port_slots():
    """端口侧风格：只有 4 个容器槽，而且**数组下标顺序与真机侧不同**；缺 (material,3)、多 (material,4)。"""
    return [
        slot(0, 2, 222, 108, True, True, None, 0, sem={"role": "material", "materialIndex": "1"}),
        slot(1, 0, 152, 58, True, False, MOD, 1, sem={"role": "target", "targetItem": MOD}),
        slot(2, 1, 194, 108, True, True, None, 0, sem={"role": "material", "materialIndex": "0"}),
        slot(3, 4, 250, 108, True, True, None, 0, sem={"role": "material", "materialIndex": "3"}),
    ]


def nodes_cr1():
    """真机侧 CR1 的节点：与端口 DP1 逐族对照（frame: / name: / attack_damage: / (structural r)）"""
    return [
        N(0, "frame:sword/blade", "sprite", [152, 58, 15, 15], draw=["sprite"], texture=TEX_A,
          uv=[52, 0, 15, 15], src=[15, 15], sem={"slotPath": "sword/blade"}),
        N(1, "name:sword/blade", "text", [175, 60, 40, 8], draw=["text"], text="剑刃",
          sem={"langKey": "tetra.variant.short_blade/iron"}),
        N(2, "attack_damage:iron:0/0", "text", [20, 30, 42, 8], draw=["text"], text="4.5",
          value=0, sem={"statKey": "attack_damage", "part": "0/0"}),
        N(3, "r/0/0/0/0", "fill", [10, 10, 4, 4], draw=["fill"], fill="#FF112233"),
        N(4, "tab:hit:0", "fill", [270, 20, 40, 20], draw=["fill"], fill="#00000000"),
    ]


def nodes_dp1():
    return [
        # node_coord：x 152→155
        N(0, "frame:sword/blade", "sprite", [155, 58, 15, 15], draw=["sprite"], texture=TEX_A,
          uv=[52, 0, 15, 15], src=[15, 15], sem={"slotPath": "sword/blade"}),
        # node_text：文字不同
        N(1, "name:sword/blade", "text", [175, 60, 40, 8], draw=["text"], text="Blade",
          sem={"langKey": "tetra.variant.short_blade/iron"}),
        # node_action：value 不同
        N(2, "attack_damage:iron:0/0", "text", [20, 30, 42, 8], draw=["text"], text="4.5",
          value=5, sem={"statKey": "attack_damage", "part": "0/0"}),
        # node_coord：(structural r) 族
        N(3, "r/0/0/0/0", "fill", [10, 12, 4, 4], draw=["fill"], fill="#FF112233"),
        # node_extra：端口多出
        N(4, "extra:node", "fill", [5, 5, 3, 3], draw=["fill"], fill="#FF000000"),
    ]


def nodes_cr2():
    return [
        N(0, "grow:blade", "text", [120, 60, 200, 14], draw=["text"], text="剑刃",
          sem={"moduleKey": "blade", "langKey": "tetra.module.sword/blade.name"}),
        N(1, "gname:blade", "text", [136, 63, 180, 8], draw=["text"], text="剑刃"),
    ]


def nodes_dp2():
    return [
        # node_missing：grow:blade 在 B 侧没有
        N(0, "gname:blade", "text", [136, 63, 180, 8], draw=["text"], text="Blade"),
    ]


def build():
    A = []
    B = []

    # ---------------- CR1 / DP1：page=NONE modules=-1 ----------------
    st1a = {"modules": -1, "group": -1, "special": -1, "variant": -1, "material_page": -1}
    st1b = {"modules": -1, "group": -1, "special": -1, "variant": -1, "material_page": 0}
    ia_a = [ia(0, "craft_bg", "craft", 0, True, [200, 150, 46, 15], "com.example.chasm.tetra.TetraPort", None, True)]
    ia_b = [ia(0, "craft_bg", "do_craft", 0, True, [200, 150, 46, 15], None, None, False)]
    A.append(line("tetra:workbench", st1a, nodes_cr1(), real_slots(), ia_a, seq=1,
                  sem={"page": "NONE", "screen": "WorkbenchScreen"}, stateDecl=DECL,
                  panel=[320, 240], title="加工台"))
    B.append(line("tetra:workbench", st1b, nodes_dp1(), port_slots(), ia_b, seq=1,
                  sem={"page": "NONE", "selectedSlot": None}, stateDecl=DECL,
                  panel=[320, 240], title="加工台"))

    # ---------------- CR2 / DP2：page=LIST modules=0 ----------------
    st2a = {"modules": 0, "group": -1, "special": -1, "variant": -1, "material_page": -1}
    st2b = {"modules": 0, "group": -1, "special": -1, "variant": -1, "material_page": 0}
    A.append(line("tetra:workbench", st2a, nodes_cr2(), real_slots(), [], seq=2,
                  sem={"page": "LIST"}, stateDecl=DECL, panel=[320, 240], title="加工台"))
    B.append(line("tetra:workbench", st2b, nodes_dp2(), port_slots(), [], seq=2,
                  sem={"page": "LIST"}, stateDecl=DECL, panel=[320, 240], title="加工台"))

    # ---------------- CR3：与 CR1 在 (gui,page,modules) 上相同 → --pair-by 碰撞 ----------------
    st3a = {"modules": -1, "group": 2, "special": -1, "variant": -1, "material_page": -1}
    A.append(line("tetra:workbench", st3a,
                  [N(0, "frame:sword/hilt", "sprite", [152, 80, 15, 15], draw=["sprite"], texture=TEX_A)],
                  real_slots(), [], seq=3, sem={"page": "NONE"}, stateDecl=DECL,
                  panel=[320, 240], title="加工台"))

    # ---------------- CR4：A 侧独有（page=TWEAK） ----------------
    st4a = {"modules": 3, "group": -1, "special": 1000, "variant": -1, "material_page": -1}
    A.append(line("tetra:workbench", st4a,
                  [N(0, "tw_title", "text", [10, 20, 60, 8], draw=["text"], text="调平")],
                  [], [], seq=4, sem={"page": "TWEAK"}, stateDecl=DECL,
                  panel=[320, 240], title="加工台"))

    # ---------------- DP4：B 侧独有（page=MATERIAL） ----------------
    st4b = {"modules": 0, "group": 3, "special": -1, "variant": 1, "material_page": 0}
    B.append(line("tetra:workbench", st4b,
                  [N(0, "detail_title", "text", [69, 63, 50, 8], draw=["text"], text="铁刃")],
                  port_slots(), [], seq=4, sem={"page": "MATERIAL"}, stateDecl=DECL,
                  panel=[320, 240], title="加工台"))

    a_text = "\n".join(json.dumps(x, ensure_ascii=False, separators=(",", ":")) for x in A)
    b_text = "\n".join(json.dumps(x, ensure_ascii=False, separators=(",", ":")) for x in B)
    with open(os.path.join(OUT, "c-real.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        f.write(a_text + "\n")
    with open(os.path.join(OUT, "d-port.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        f.write(b_text + "\n")
    print("c-real.jsonl:", len(A), "条（真机侧风格）")
    print("d-port.jsonl:", len(B), "条（端口侧风格）")


if __name__ == "__main__":
    build()
