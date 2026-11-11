# 第三方开源软件与素材声明

本文件是 **CodeBuddy Account Manager** 的权威开源声明，同时适用于 GitHub 源码仓库与 JetBrains Marketplace 分发包（本文件随插件 jar 一起分发）。README 与插件市场描述中的「开源软件与素材声明」段落与本文口径一致，并链接回本文件。

## 一、随插件包分发的第三方组件

| 项目 | 版本 | 授权方式 | 用途 | 包内位置 | 修改情况 |
| --- | --- | --- | --- | --- | --- |
| [Gson](https://github.com/google/gson) | 2.11.0 | Apache-2.0 | JSON 解析 | `lib/gson-2.11.0.jar` | 未修改 |
| [error_prone_annotations](https://github.com/google/error-prone) | 2.27.0 | Apache-2.0 | Gson 的传递依赖（注解） | `lib/error_prone_annotations-2.27.0.jar` | 未修改 |

Apache-2.0 第 4 条要求的许可证全文见本文「附录 B」。上述 jar 中未包含需要另行转附的 NOTICE 文件。

插件运行时的 Kotlin 标准库与 IntelliJ Platform API 由宿主 IDE 提供，不随本插件分发（`kotlin.stdlib.default.dependency=false`）；CodeBuddy 插件（`com.tencent.cloud.codingcopilot`）是本插件读写凭据所依赖的宿主插件，不属于本插件分发物，也不是本项目的开源依赖。

## 二、桌宠形象与素材：dsh-pet

| 项目 | 授权方式 | 使用内容 |
| --- | --- | --- |
| [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet) | 代码：MIT（Copyright (c) 2026 PC2005-cloud）；形象/素材：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)（署名 · 非商业 · 相同方式共享） | 桌宠形象、动画、表情包、点击音效 |

按原项目 README「许可」段，完整义务如下：

- **代码**：MIT。本插件只使用其素材，未使用其源码；MIT 全文仍附于「附录 A」以便核对。
- **素材**：动画 / 提示词 / 源视频允许开源使用，具体授权参考角色原型的形象授权（即下方 CC BY-NC-SA 4.0）。
- **二创署名**：基于本项目的衍生 / 改版 / 换皮作品，在 **GitHub 仓库、产品官网等公开渠道**上须附上原作者 GitHub 地址：<https://github.com/PC2005-cloud/dsh-pet>。
- **形象出处**：宠物形象为社区二创的 DeepSeek「大肥鱼」（鲸鱼娘），**非 DeepSeek 官方形象**；原型「溟月」出自 B 站 [@上善无形](https://space.bilibili.com/4456176)，DeepSeek 版（女仆鲸鱼娘）设计出自 B 站 [@ZipZipPipe](https://space.bilibili.com/4168597)。
- **形象授权**：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)（署名 · 非商业 · 相同方式共享），[声明原文](https://www.bilibili.com/opus/1247156596114456581)：「大肥鱼是遵守CC BY-NC-SA 4.0协议的形象，不可以以任何形式商业化。若您非常喜爱该形象，希望其拥有实体，您可以自行定制。」

### 本插件的修改与合规做法

- **修改说明**（CC BY 要求标明）：把原项目 VP9-alpha `webm` 动画逐帧转为自定义 `.petanim` 容器（JPEG 颜色层 + 半分辨率 PNG 灰度掩码，25fps），27 张 `assets/memes` 表情包重命名为 `m01`…`m27`，点击音效随包保留为 `pet/click-duck.wav`；均为格式转换 / 重命名，**未二次绘制或修改角色形象**。
- **非商业**：插件在 JetBrains Marketplace 免费分发（`pricingModel=FREE`），不用于任何商业用途，也不单独出售上述素材。
- **公开渠道署名**：GitHub 仓库 README 与本文件、JetBrains Marketplace 插件描述、插件设置页均保留原作者仓库地址、形象出处与上述许可信息。
- **预览图**：`docs/screenshots/pet.png` 使用同一桌宠形象，同样按 CC BY-NC-SA 4.0 提供；README 与 Marketplace 描述均在图片旁标注来源与许可，说明本插件已接入 dsh-pet。
- **相同方式共享**：`src/main/resources/pet/**` 中由原形象转换 / 改编的动画、表情包与音效，按 CC BY-NC-SA 4.0 提供，不得商业化；本仓库其余代码的授权见 README「License」段，不受该许可覆盖。
- 桌宠形象为社区二创，非 DeepSeek 官方形象；本插件与 DeepSeek、CodeBuddy 官方无隶属或背书关系。

## 三、构建与测试期工具（不随插件包分发）

| 项目 | 版本 | 授权方式 | 用途 |
| --- | --- | --- | --- |
| [Kotlin JVM Gradle Plugin](https://github.com/JetBrains/kotlin) | 2.4.0 | Apache-2.0 | 编译 Kotlin 源码 |
| [IntelliJ Platform Gradle Plugin](https://github.com/JetBrains/intellij-platform-gradle-plugin) | 2.2.1 | Apache-2.0 | 构建、打包、校验插件 |
| [JUnit Jupiter](https://github.com/junit-team/junit5) | 5.11.3 | [EPL-2.0](https://www.eclipse.org/legal/epl-v20.html) | 单元测试 |
| IntelliJ IDEA / IntelliJ Platform | 2026.2 (build 262) | 由宿主 IDE 提供 | 本地 `local(...)` 构建目标与运行时 |

上述工具仅在开发、测试、打包阶段使用，不包含在分发包中；其许可证全文以各自项目仓库为准。

## 四、声明位置

- GitHub 仓库：[README.md](README.md)「开源软件与素材声明」段 + 本文件。
- JetBrains Marketplace：插件描述「开源软件与素材声明」段（来源为 `src/main/resources/META-INF/plugin.xml` 的 `<description>`，同步维护在 `docs/marketplace-description.html`），内容与本文件一致。
- 分发包：本文件由 `build.gradle.kts` 的 `processResources` 复制进插件 jar，随 Marketplace 下载的 zip 一并提供。

## 附录 A：dsh-pet 代码 MIT License 全文

来源：<https://github.com/PC2005-cloud/dsh-pet/blob/main/LICENSE>

```text
MIT License

Copyright (c) 2026 PC2005-cloud

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 附录 B：Apache License 2.0 全文

适用于 Gson、error_prone_annotations 及构建期的 Kotlin / IntelliJ Platform Gradle Plugin。来源：<https://www.apache.org/licenses/LICENSE-2.0.txt>

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "{}"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright {yyyy} {name of copyright owner}

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```
