# -*- coding: utf-8 -*-
"""
生成 probe-diff 的合成 fixture：一份"真机侧"(a-real.jsonl) + 一份"端口侧"(b-port.jsonl)。

两侧都是**严格按 chasm.gui.probe/1 schema** 手写的 JSONL（字段逐字对齐
chasm-core/src/main/java/api/chasm/gui/decl/GuiProbe.java），每一处刻意的差异
都在下面的注释里标出它想覆盖的 probe-diff 类别。

覆盖的类别（selftest.ps1 会逐条断言这些 id 真的出现在报告里）：
  记录/状态: parse_error record_only_a record_only_b schema_mismatch
             state_keys_only_a state_keys_only_b state_value state_decl
  节点:      node_missing node_extra node_coord node_size node_type node_texture
             node_uv node_text node_color node_opacity node_action node_enabled
             node_scaled node_draw node_layer node_slot node_handler node_anim
             node_sem node_textscale node_shadow node_tooltip
             node_pair_lowconf node_key_conflict
  存储槽:    slot_missing slot_extra slot_coord slot_active slot_item slot_empty
             slot_count slot_dynamic slot_filtered slot_sem
  交互项:    ia_missing ia_extra ia_action ia_value ia_enabled ia_rect ia_handler
  行级:      line_field line_sem line_nodesCount
  重复:      record_dup

未覆盖（防御性类别，正常 dump 不会触发）：line_gui
"""
import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)))

MOD = "tetra:modular_sword"
TEX_A = "tetra:textures/gui/workbench.png"
TEX_B = "tetra-port:textures/gui/workbench.png"

# ---------------------------------------------------------------- 节点模板
def N(i, key, type_, rect, **kw):
    """一个节点的完整字段（缺一个都会被 probe-diff 当成差异，所以这里给全）。"""
    d = {
        "i": i, "key": key, "type": type_, "draw": ["none"], "layer": "BEHIND",
        "rect": rect, "texture": None, "uv": [0, 0, 0, 0], "src": [0, 0], "scaled": False,
        "tint": "#00000000", "fill": "#00000000", "text": None, "textScale": 1,
        "shadow": False, "color": "#FFFFFFFF", "hover": "#00000000", "opacity": 1,
        "delay": 0, "enabled": True, "tooltip": None, "action": None, "value": 0,
        "handler": None, "handlerClass": None, "handlerRegistered": False,
        "slot": -1, "invSlot": -1, "bound": False, "alphaTag": None, "alphaRest": 1,
        "slide": [0, 0, 0],
    }
    d.update(kw)
    return d


def slot(i, ci, x, y, active, empty, item, count, dyn=False, filt=False, sem=None):
    d = {"i": i, "containerIndex": ci, "x": x, "y": y, "active": active, "dynamic": dyn,
         "filtered": filt, "empty": empty, "item": item, "count": count}
    if sem is not None:
        d["sem"] = sem
    return d


def ia(i, node, action, value, enabled, rect, handler=None, hclass=None, reg=False):
    return {"i": i, "node": node, "action": action, "value": value, "enabled": enabled,
            "rect": rect, "handler": handler, "handlerClass": hclass, "handlerRegistered": reg}


def line(gui, state, nodes, slots, inter, **kw):
    st = dict(state)
    d = {
        "schema": "chasm.gui.probe/1",
        "seq": kw.get("seq", 1), "ts": kw.get("ts", 1758000000000),
        "mode": "change", "hash": kw.get("hash", "00000000"),
        "stateChanged": sorted(st.keys()),
        "delta": {"added": [], "removed": [], "changed": []},
        "gui": gui,
        "title": kw.get("title", "加工台"),
        "origin": kw.get("origin", [0, 0]),
        "panel": kw.get("panel", [320, 240]),
        "menu": kw.get("menu", {"syncId": 0, "clientSide": True, "bound": False,
                                "storageSlots": 4, "playerSlots": 36}),
        "sem": kw.get("sem", {}),
        "stateDecl": kw.get("stateDecl", {"modules": [-1, -1, 4], "group": [-1, -1, 99],
                                          "special": [-1, -1, 9]}),
        "state": st,
        "stateValues": kw.get("stateValues", {}),
        "slots": slots,
        "nodes": nodes,
        "interactive": inter,
        "nodesCount": kw.get("nodesCount", len(nodes)),
    }
    if "schema" in kw:
        d["schema"] = kw["schema"]
    if "title" in kw:
        d["title"] = kw["title"]
    return d


# ==================================================================================
# R1：两侧 state 完全一致的同一帧 —— 绝大部分类别都在这里
# ==================================================================================
def r1_nodes_a():
    a = []
    # 完全相同：不该产生任何差异
    a.append(N(0, "player_inventory", "sprite", [7, 83, 162, 54], draw=["sprite"],
               texture="minecraft:textures/gui/container/inventory.png"))
    a.append(N(1, "list_title", "text", [10, 20, 60, 8], draw=["text"], text="加工台"))
    # node_coord：x 差 3
    a.append(N(2, "name:sword/blade", "text", [100, 40, 40, 8], draw=["text"], text="剑刃",
               sem={"slotPath": "sword/blade", "installedModule": "short_blade/iron",
                    "langKey": "tetra.variant.short_blade/iron"}))
    # node_size：宽差 2
    a.append(N(3, "frame:sword/hilt", "sprite", [152, 44, 20, 20], draw=["sprite"], texture=TEX_A,
               uv=[0, 0, 20, 20], src=[16, 16],
               sem={"slotPath": "sword/hilt", "installedModule": None}))
    # node_type：sprite → image
    a.append(N(4, "glyph:sword/blade", "sprite", [96, 40, 8, 8], draw=["sprite"], texture=TEX_A,
               uv=[16, 0, 8, 8], src=[8, 8], sem={"slotPath": "sword/blade"}))
    # node_texture / node_uv / node_action / node_tooltip / node_handler / node_color(tint) / node_coord
    a.append(N(5, "craft_bg", "sprite", [200, 150, 46, 15], draw=["sprite"], texture=TEX_A,
               uv=[0, 0, 16, 16], src=[16, 16], tint="#FF808080",
               tooltip="制作", action="craft", value=0,
               handler="com.example.chasm.tetra.TetraPort",
               handlerClass="com.example.chasm.tetra.TetraPort$$Lambda$1", handlerRegistered=True,
               sem={"builder": "TetraUi.buildMaterialDetail"}))
    # node_color：fill 差
    a.append(N(6, "detail_flash", "fill", [50, 60, 239, 69], draw=["fill"], fill="#40FFFFFF"))
    # node_opacity
    a.append(N(7, "detail_close", "text", [265, 56, 6, 10], draw=["text"], text="x", opacity=1))
    # node_text + node_enabled（langKey 两侧都给了，便于报告直接定位语言键）
    a.append(N(8, "craft_label", "text", [215, 154, 20, 8], draw=["text"], text="制作",
               enabled=True, sem={"langKey": "tetra.action.craft"}))
    # node_draw
    a.append(N(9, "detail_bg", "sprite", [50, 57, 239, 69], draw=["sprite"], texture=TEX_A,
               uv=[0, 32, 239, 69], src=[239, 69]))
    # node_layer
    a.append(N(10, "tab_bar", "fill", [270, 20, 40, 60], draw=["fill"], layer="BEHIND", fill="#FF000000"))
    # node_slot：slot / invSlot / bound 差
    a.append(N(11, "slot:sword/blade", "text", [70, 40, 30, 7], draw=["text"], text="剑刃",
               slot=0, invSlot=-1, bound=True,
               sem={"slotPath": "sword/blade", "langKey": "tetra.slot.sword/blade"}))
    # node_anim：delay / alphaTag / alphaRest / slide 差
    a.append(N(12, "detail_back", "text", [46, 122, 40, 8], draw=["text"], text="返回",
               delay=0, alphaTag=None, alphaRest=1, slide=[0, 0, 0]))
    # node_textscale + node_shadow + node_sem
    a.append(N(13, "name:sword/hilt", "text", [100, 60, 40, 8], draw=["text"], text="握柄",
               textScale=1, shadow=False,
               sem={"slotPath": "sword/hilt", "installedModule": None,
                    "langKey": "tetra.slot.sword/hilt"}))
    # node_scaled
    a.append(N(14, "material_frame:0", "sprite", [150, 105, 16, 16], draw=["sprite"], texture=TEX_A,
               uv=[32, 0, 16, 16], src=[16, 16], scaled=False))
    # node_color（color / hover）
    a.append(N(15, "detail_title", "text", [69, 63, 50, 8], draw=["text"], text="剑刃",
               color="#FFFFFFFF", hover="#00000000"))
    # ---- 尾部：key 缺失 / key 冲突 / A 独有 ----
    # 低置信配对（两侧 key 都是 null，同 type 同位置）
    a.append(N(16, None, "sprite", [60, 40, 16, 16], draw=["sprite"], texture=TEX_A))
    # node_key_conflict：同侧重复 key
    a.append(N(17, "dup:key", "fill", [10, 200, 5, 5], draw=["fill"], fill="#FF00FF00"))
    a.append(N(18, "dup:key", "fill", [20, 200, 5, 5], draw=["fill"], fill="#FF00FF00"))
    # node_missing：A 有、B 没有的有 key 节点
    a.append(N(19, "frame:sword/blade", "sprite", [200, 180, 20, 20], draw=["sprite"], texture=TEX_A,
               uv=[0, 16, 20, 20], src=[20, 20],
               sem={"slotPath": "sword/blade", "installedModule": "short_blade/iron",
                    "builder": "TetraUi.buildSlotNode"}))
    # node_missing：A 有、B 没有的无 key 节点（位置远离 B 的候选池）
    a.append(N(20, None, "text", [300, 300, 20, 8], draw=["text"], text="仅 A 侧有的无 key 节点"))
    return a


def r1_nodes_b():
    b = []
    b.append(N(0, "player_inventory", "sprite", [7, 83, 162, 54], draw=["sprite"],
               texture="minecraft:textures/gui/container/inventory.png"))
    b.append(N(1, "list_title", "text", [10, 20, 60, 8], draw=["text"], text="加工台"))
    # node_coord：x=103（A 是 100）
    b.append(N(2, "name:sword/blade", "text", [103, 40, 40, 8], draw=["text"], text="剑刃",
               sem={"slotPath": "sword/blade", "installedModule": "short_blade/iron",
                    "langKey": "tetra.variant.short_blade/iron"}))
    # node_size：宽 22（A 是 20）
    b.append(N(3, "frame:sword/hilt", "sprite", [152, 44, 22, 20], draw=["sprite"], texture=TEX_A,
               uv=[0, 0, 20, 20], src=[16, 16],
               sem={"slotPath": "sword/hilt", "installedModule": None}))
    # node_type：image（A 是 sprite）
    b.append(N(4, "glyph:sword/blade", "image", [96, 40, 8, 8], draw=["sprite"], texture=TEX_A,
               uv=[16, 0, 8, 8], src=[8, 8], sem={"slotPath": "sword/blade"}))
    # 纹理/UV/源尺寸/action/value/tooltip/handler/tint/坐标 全差
    b.append(N(5, "craft_bg", "sprite", [202, 150, 46, 15], draw=["sprite"], texture=TEX_B,
               uv=[16, 0, 16, 16], src=[16, 32], tint="#FF606060",
               tooltip="Craft", action="do_craft", value=7,
               handler=None, handlerClass=None, handlerRegistered=False,
               sem={"builder": "TetraUi.buildMaterialDetail"}))
    # node_color：fill 差
    b.append(N(6, "detail_flash", "fill", [50, 60, 239, 69], draw=["fill"], fill="#20FFFFFF"))
    # node_opacity 差
    b.append(N(7, "detail_close", "text", [265, 56, 6, 10], draw=["text"], text="x", opacity=0.5))
    # node_text + node_enabled 差
    b.append(N(8, "craft_label", "text", [215, 154, 20, 8], draw=["text"], text="Craft",
               enabled=False, sem={"langKey": "tetra.action.craft"}))
    # node_draw 差
    b.append(N(9, "detail_bg", "sprite", [50, 57, 239, 69], draw=["sprite", "fill"], texture=TEX_A,
               uv=[0, 32, 239, 69], src=[239, 69], fill="#10FFFFFF"))
    # node_layer 差
    b.append(N(10, "tab_bar", "fill", [270, 20, 40, 60], draw=["fill"], layer="ABOVE", fill="#FF000000"))
    # node_slot 差
    b.append(N(11, "slot:sword/blade", "text", [70, 40, 30, 7], draw=["text"], text="剑刃",
               slot=1, invSlot=5, bound=False,
               sem={"slotPath": "sword/blade", "langKey": "tetra.slot.sword/blade"}))
    # node_anim 差
    b.append(N(12, "detail_back", "text", [46, 122, 40, 8], draw=["text"], text="返回",
               delay=0.4, alphaTag="fade", alphaRest=0.3, slide=[-4, 0, 120]))
    # node_textscale + node_shadow + node_sem 差
    b.append(N(13, "name:sword/hilt", "text", [100, 60, 40, 8], draw=["text"], text="握柄",
               textScale=1.5, shadow=True,
               sem={"slotPath": "sword/hilt", "installedModule": "basic_hilt/stick",
                    "langKey": "tetra.variant.basic_hilt/stick"}))
    # node_scaled 差
    b.append(N(14, "material_frame:0", "spriteRegion", [150, 105, 16, 16], draw=["sprite"], texture=TEX_A,
               uv=[32, 0, 16, 16], src=[16, 16], scaled=True))
    # node_color 差（color / hover）
    b.append(N(15, "detail_title", "text", [69, 63, 50, 8], draw=["text"], text="剑刃",
               color="#FFFFFF80", hover="#40FFFFFF"))
    # ---- 尾部 ----
    # 低置信配对：key 也是 null，位置只差一点点
    b.append(N(16, None, "sprite", [61, 43, 16, 16], draw=["sprite"], texture=TEX_A))
    # node_key_conflict：B 侧也重复同一 key
    b.append(N(17, "dup:key", "fill", [10, 200, 5, 5], draw=["fill"], fill="#FF00FF00"))
    b.append(N(18, "dup:key", "fill", [21, 200, 5, 5], draw=["fill"], fill="#FF00FF00"))
    # node_extra：B 侧独有的有 key 节点
    b.append(N(19, "extra_thing:xyz", "text", [280, 60, 30, 8], draw=["text"], text="端口多出来的东西",
               sem={"builder": "TetraUi.addPanel"}))
    return b


def r1_interactive(nodes_a, nodes_b):
    def idx(nodes, key):
        for n in nodes:
            if n["key"] == key:
                return n["i"]
        raise KeyError(key)

    craft_a = [n for n in nodes_a if n["key"] == "craft_bg"][0]
    craft_b = [n for n in nodes_b if n["key"] == "craft_bg"][0]
    a = [
        ia(idx(nodes_a, "craft_bg"), "craft_bg", "craft", 0, True, craft_a["rect"],
           "com.example.chasm.tetra.TetraPort", "com.example.chasm.tetra.TetraPort$$Lambda$1", True),
        ia(idx(nodes_a, "detail_back"), "detail_back", "back", 0, True, [46, 122, 40, 8],
           "com.example.chasm.tetra.TetraPort", None, True),
        ia(idx(nodes_a, "slot:sword/blade"), "slot:sword/blade", "select_slot", 0, True, [70, 40, 30, 7],
           "com.example.chasm.tetra.TetraPort", None, True),
        # ia_missing：B 侧没有这条
        ia(idx(nodes_a, "list_title"), "grow:blade", "pick_variant", 3, True, [120, 60, 200, 14],
           "com.example.chasm.tetra.TetraPort", None, True),
        # ia_missing：A 侧剩余条数多于 B 侧时，次序回退也配不完 → 如实记成"交互项缺失"
        ia(idx(nodes_a, "tab_bar"), "tab_bar", "page", 1, True, [270, 20, 40, 60],
           "com.example.chasm.tetra.TetraPort", None, True),
    ]
    b = [
        # ia_value + ia_handler + ia_rect（rect 跟着节点 rect 差 2）
        ia(idx(nodes_b, "craft_bg"), "craft_bg", "craft", 1, True, craft_b["rect"],
           None, None, False),
        # ia_action：action 名不同（back → return），会退化成按 node 名配对
        ia(idx(nodes_b, "detail_back"), "detail_back", "return", 0, True, [46, 122, 40, 8],
           "com.example.chasm.tetra.TetraPort", None, True),
        # ia_enabled
        ia(idx(nodes_b, "slot:sword/blade"), "slot:sword/blade", "select_slot", 0, False, [70, 40, 30, 7],
           "com.example.chasm.tetra.TetraPort", None, True),
        # ia_extra：B 侧多出
        ia(idx(nodes_b, "extra_thing:xyz"), "extra_ia", "foo", 0, True, [280, 60, 30, 8], None, None, False),
    ]
    return a, b


def r1_slots():
    # 注意：probe-diff/2 起默认 --slots-scope=role，槽位按 (sem.role, containerIndex) 配对。
    # 这里让两侧 (role, containerIndex) 在相同数组下标上一致 —— 这份 fixture 对默认模式与
    # --slots-scope real-all 给出**相同**的配对结果，两边都能当基准。
    # "两侧数组下标/role 故意错位" 的场景放在 fixture2（c-real / d-port）。
    a = [
        slot(0, 0, 152, 58, True, False, MOD, 1,
             sem={"role": "target", "selectedSlotPath": "sword/blade", "targetItem": MOD}),
        # slot_coord（y 差 2）+ slot_dynamic / slot_filtered + slot_sem（materialIndex 差）
        slot(1, 1, 152, 58, True, True, None, 0, dyn=False, filt=False,
             sem={"role": "material", "materialIndex": "0"}),
        # slot_active 差
        slot(2, 2, 152, 80, True, True, None, 0, sem={"role": "material", "materialIndex": "1"}),
        # slot_count 差
        slot(3, 3, 152, 102, True, False, "minecraft:iron_ingot", 5, sem={"role": "material"}),
        # slot_missing：A 有 (material,4)，B 没有
        slot(4, 4, 152, 124, True, True, None, 0, sem={"role": "material", "materialIndex": "3"}),
    ]
    b = [
        # slot_item + slot_empty + slot_count（A 有物品）
        slot(0, 0, 152, 58, True, True, None, 0,
             sem={"role": "target", "selectedSlotPath": "sword/blade", "targetItem": None}),
        # slot_coord（y 60）+ slot_dynamic / slot_filtered + slot_sem（materialIndex 9）
        slot(1, 1, 152, 60, True, True, None, 0, dyn=True, filt=True,
             sem={"role": "material", "materialIndex": "9"}),
        # slot_active 差
        slot(2, 2, 152, 80, False, True, None, 0, sem={"role": "material", "materialIndex": "1"}),
        # slot_count 差
        slot(3, 3, 152, 102, True, False, "minecraft:iron_ingot", 3, sem={"role": "material"}),
        # slot_extra：B 多出 (material,99)
        slot(4, 99, 152, 146, True, True, None, 0, sem={"role": "material", "materialIndex": "99"}),
    ]
    return a, b


def build():
    A = []
    B = []

    na = r1_nodes_a()
    nb = r1_nodes_b()
    ia_a, ia_b = r1_interactive(na, nb)
    sa, sb = r1_slots()

    st1 = {"modules": -1, "group": -1, "special": -1}
    # ---- R1：state 完全一致 → exact 配对；行级 / 节点 / 槽 / 交互 的所有差异都在这 ----
    A.append(line("tetra:workbench", st1, na, sa, ia_a,
                  seq=1, title="加工台", origin=[0, 0], panel=[320, 240],
                  menu={"syncId": 0, "clientSide": True, "bound": False,
                        "storageSlots": 4, "playerSlots": 36},
                  sem={"page": "NONE", "selectedSlot": None, "targetItem": None},
                  stateDecl={"modules": [-1, -1, 4], "group": [-1, -1, 99], "special": [-1, -1, 9]}))
    B.append(line("tetra:workbench", st1, nb, sb, ia_b,
                  seq=1, title="工作台", origin=[0, 2], panel=[320, 242],
                  menu={"syncId": 0, "clientSide": True, "bound": True,
                        "storageSlots": 4, "playerSlots": 36},
                  sem={"page": "LIST", "selectedSlot": "sword/blade", "targetItem": MOD},
                  stateDecl={"modules": [-1, -1, 3], "group": [-1, -1, 99], "special": [-1, -1, 9]}))

    # ---- R2：state 键名相同、值不同 → state-values-differ 降级配对 + state_value ----
    st2a = {"modules": 0, "group": -1, "special": -1, "variant": -1}
    st2b = {"modules": 0, "group": 0, "special": -1, "variant": -1}
    n2 = lambda: [N(0, "list_title", "text", [10, 20, 60, 8], draw=["text"], text="加工台")]
    A.append(line("tetra:workbench", st2a, n2(), [slot(0, 0, 152, 58, True, True, None, 0)],
                  [ia(0, "list_title", "pick_group", 0, True, [10, 20, 60, 8])],
                  seq=2, sem={"page": "LIST"}, stateDecl={"modules": [-1, -1, 4], "group": [-1, -1, 99],
                                                          "special": [-1, -1, 9], "variant": [-1, -1, 9]},
                  # stateValues（大值键）：b 的值不同 → state_value；c 只在 B 侧有 → state_keys_only_b
                  stateValues={"previewName": "short_blade/iron", "b": "1"}))
    B.append(line("tetra:workbench", st2b, n2(), [slot(0, 0, 152, 58, True, True, None, 0)],
                  [ia(0, "list_title", "pick_group", 0, True, [10, 20, 60, 8])],
                  seq=2, sem={"page": "LIST"}, stateDecl={"modules": [-1, -1, 4], "group": [-1, -1, 99],
                                                          "special": [-1, -1, 9], "variant": [-1, -1, 9]},
                  stateValues={"previewName": "short_blade/iron", "b": "2", "c": "只 B 侧有"},
                  # line_nodesCount：B 侧 nodesCount 与真实 nodes[] 长度不一致（dump 自身不自洽）
                  nodesCount=99))

    # ---- R3：state 键名集合不同 → order-guess 降级配对 + state_keys_only_a/b ----
    st3a = {"modules": 0, "group": 2, "special": -1}
    st3b = {"modules": 0, "group": 2, "page": 1}
    n3 = lambda: [N(0, "detail_title", "text", [69, 63, 50, 8], draw=["text"], text="铁刃")]
    A.append(line("tetra:workbench", st3a, n3(), [slot(0, 0, 152, 58, True, False, MOD, 1)], [],
                  seq=3, sem={"page": "MATERIAL"},
                  stateDecl={"modules": [-1, -1, 4], "group": [-1, -1, 99], "special": [-1, -1, 9]}))
    B.append(line("tetra:workbench", st3b, n3(), [slot(0, 0, 152, 58, True, False, MOD, 1)], [],
                  seq=3, sem={"page": "MATERIAL"},
                  stateDecl={"modules": [-1, -1, 4], "group": [-1, -1, 99], "page": [-1, -1, 9]}))

    # ---- R6：state 一致但 B 侧 schema 更新 → schema_mismatch ----
    st6 = {"modules": 5, "group": 5, "special": 5}
    n6 = lambda: [N(0, "list_title", "text", [10, 20, 60, 8], draw=["text"], text="加工台")]
    A.append(line("tetra:workbench", st6, n6(), [], [], seq=4))
    B.append(line("tetra:workbench", st6, n6(), [], [], seq=4, schema="chasm.gui.probe/2"))

    # ---- record_dup：A 侧把 R1 又写了一遍（同 gui + 同 state） ----
    A.append(line("tetra:workbench", st1, na, sa, ia_a, seq=5))

    # ---- record_only_a：A 侧独有的界面 ----
    A.append(line("tetra:holosphere", {"modules": 1, "group": -1, "special": -1},
                  [N(0, "holo_bg", "sprite", [0, 0, 100, 100], draw=["sprite"], texture=TEX_A)],
                  [], [], seq=6))

    # ---- record_only_b：B 侧独有的界面 ----
    B.append(line("tetra:geode", {"modules": 1, "group": -1, "special": -1},
                  [N(0, "geode_bg", "sprite", [0, 0, 100, 100], draw=["sprite"], texture=TEX_B)],
                  [], [], seq=6))

    # ---- line_gui：两侧 gui 都是 null（GuiProbe 在 guiId 为 null 时就是这么写的） ----
    n7 = lambda: [N(0, "null_gui_bg", "sprite", [0, 0, 10, 10], draw=["sprite"], texture=TEX_A)]
    A.append(line(None, {"modules": 9, "group": 9, "special": 9}, n7(), [], [], seq=8, title="无 id 界面"))
    B.append(line(None, {"modules": 9, "group": 9, "special": 9}, n7(), [], [], seq=8, title="无 id 界面"))

    # ---- parse_error：A 侧一行被截断（模拟写文件时进程被杀） ----
    a_text = "\n".join(json.dumps(x, ensure_ascii=False, separators=(",", ":")) for x in A)
    a_text += '\n{"schema":"chasm.gui.probe/1","seq":7,"gui":"tetra:truncated",'
    b_text = "\n".join(json.dumps(x, ensure_ascii=False, separators=(",", ":")) for x in B)

    with open(os.path.join(OUT, "a-real.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        f.write(a_text + "\n")
    with open(os.path.join(OUT, "b-port.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        f.write(b_text + "\n")

    print("a-real.jsonl:", len(A), "条合法记录 + 1 条截断行")
    print("b-port.jsonl:", len(B), "条合法记录")


if __name__ == "__main__":
    build()
