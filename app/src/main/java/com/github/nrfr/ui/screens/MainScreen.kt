package com.github.nrfr.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.nrfr.R
import com.github.nrfr.data.CountryPresets
import com.github.nrfr.data.PresetCarriers
import com.github.nrfr.manager.CarrierConfigManager
import com.github.nrfr.model.SimCardInfo
import com.github.nrfr.ui.theme.OnSuccessGreenContainer
import com.github.nrfr.ui.theme.SuccessGreenContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onShowAbout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedSimCard by remember { mutableStateOf<SimCardInfo?>(null) }
    var selectedCountryCode by remember { mutableStateOf("") }
    var customCountryCode by remember { mutableStateOf("") }
    var isCustomCountryCode by remember { mutableStateOf(false) }
    var selectedCarrier by remember { mutableStateOf<PresetCarriers.CarrierPreset?>(null) }
    var customCarrierName by remember { mutableStateOf("") }
    var isCountryCodeMenuExpanded by remember { mutableStateOf(false) }
    var isCarrierMenuExpanded by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    fun refreshConfig(delayed: Boolean) {
        if (delayed) {
            scope.launch {
                delay(800)
                refreshTrigger += 1
            }
        } else {
            refreshTrigger += 1
        }
    }

    // 获取实际的 SIM 卡信息
    val simCards = remember(context, refreshTrigger) { CarrierConfigManager.getSimCards(context) }

    // 当 simCards 更新时，自动选择或刷新选中的卡
    LaunchedEffect(simCards) {
        if (selectedSimCard != null) {
            selectedSimCard = simCards.find { it.slot == selectedSimCard?.slot }
        }
        if (selectedSimCard == null && simCards.isNotEmpty()) {
            selectedSimCard = simCards.first()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            modifier = Modifier.size(36.dp),
                            contentDescription = "App Icon",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Nrfr",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShowAbout) {
                        Icon(Icons.Default.Info, contentDescription = "关于")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. SIM 卡选择器（卡片式分段选择）
            Text(
                text = "选择 SIM 卡",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            SimCardSelector(
                simCards = simCards,
                selectedSimCard = selectedSimCard,
                onSimCardSelected = { selectedSimCard = it }
            )

            // 2. 当前选中卡槽生效状态卡片
            selectedSimCard?.let { simCard ->
                CurrentConfigCard(simCard = simCard)
            }

            // 3. 配置目标参数卡片
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "目标运营商配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // 国家码选择
                    CountryCodeSelector(
                        selectedCountryCode = selectedCountryCode,
                        isCustomCountryCode = isCustomCountryCode,
                        customCountryCode = customCountryCode,
                        isExpanded = isCountryCodeMenuExpanded,
                        onExpandedChange = { isCountryCodeMenuExpanded = it },
                        onCountryCodeSelected = { code ->
                            selectedCountryCode = code
                            isCustomCountryCode = false
                        },
                        onCustomSelected = {
                            isCustomCountryCode = true
                            selectedCountryCode = customCountryCode
                        }
                    )

                    // 自定义国家码输入框
                    if (isCustomCountryCode) {
                        CustomCountryCodeInput(
                            value = customCountryCode,
                            onValueChange = {
                                if (it.length <= 2 && it.all { char -> char.isLetter() }) {
                                    customCountryCode = it.uppercase()
                                    selectedCountryCode = it.uppercase()
                                }
                            }
                        )
                    }

                    // 运营商选择
                    CarrierSelector(
                        selectedCarrier = selectedCarrier,
                        isExpanded = isCarrierMenuExpanded,
                        onExpandedChange = { isCarrierMenuExpanded = it },
                        onCarrierSelected = { carrier ->
                            selectedCarrier = carrier
                            customCarrierName = carrier.displayName
                        }
                    )

                    // 自定义运营商名称输入框
                    if (selectedCarrier?.name == "自定义") {
                        CustomCarrierNameInput(
                            value = customCarrierName,
                            onValueChange = { customCarrierName = it }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 4. 底部操作按钮
            ActionButtons(
                selectedSimCard = selectedSimCard,
                selectedCountryCode = selectedCountryCode,
                isCustomCountryCode = isCustomCountryCode,
                customCountryCode = customCountryCode,
                selectedCarrier = selectedCarrier,
                customCarrierName = customCarrierName,
                onReset = { simCard ->
                    try {
                        val delayedRefresh = CarrierConfigManager.resetCarrierConfig(context, simCard.subId)
                        Toast.makeText(context, "设置已还原", Toast.LENGTH_SHORT).show()
                        refreshConfig(delayedRefresh)
                        selectedCountryCode = ""
                        selectedCarrier = null
                        customCarrierName = ""
                    } catch (e: Exception) {
                        Toast.makeText(context, "还原失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                onSave = { simCard ->
                    try {
                        val carrierName = if (selectedCarrier?.name == "自定义") {
                            customCarrierName.takeIf { it.isNotEmpty() }
                        } else {
                            selectedCarrier?.displayName
                        }
                        val countryCode = if (isCustomCountryCode) {
                            customCountryCode.takeIf { it.length == 2 }
                        } else {
                            selectedCountryCode
                        }
                        val delayedRefresh = CarrierConfigManager.setCarrierConfig(
                            context,
                            simCard.subId,
                            countryCode,
                            carrierName
                        )
                        Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                        refreshConfig(delayedRefresh)
                    } catch (e: Exception) {
                        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SimCardSelector(
    simCards: List<SimCardInfo>,
    selectedSimCard: SimCardInfo?,
    onSimCardSelected: (SimCardInfo) -> Unit
) {
    if (simCards.isEmpty()) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "未检测到活跃的 SIM 卡，请检查卡槽状态",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        simCards.forEach { simCard ->
            val isSelected = selectedSimCard?.slot == simCard.slot
            ElevatedCard(
                onClick = { onSimCardSelected(simCard) },
                modifier = Modifier.weight(1f),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                ),
                elevation = CardDefaults.elevatedCardElevation(
                    defaultElevation = if (isSelected) 3.dp else 1.dp
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${simCard.slot}",
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SIM ${simCard.slot}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = simCard.carrierName.ifBlank { "卡槽 ${simCard.slot}" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentConfigCard(simCard: SimCardInfo) {
    val hasOverride = simCard.currentConfig.isNotEmpty()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "当前卡槽生效状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasOverride) SuccessGreenContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (hasOverride) "✓ 已生效覆盖" else "系统默认配置",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (hasOverride) OnSuccessGreenContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            if (!hasOverride) {
                Text(
                    text = "当前 SIM 卡正使用系统原生运营商参数，尚未写入自定义国家码或覆盖配置。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                simCard.currentConfig.forEach { (key, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryCodeSelector(
    selectedCountryCode: String,
    isCustomCountryCode: Boolean,
    customCountryCode: String,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCountryCodeSelected: (String) -> Unit,
    onCustomSelected: () -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = isExpanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = when {
                isCustomCountryCode -> "自定义 (${customCountryCode.ifEmpty { "未输入" }})"
                selectedCountryCode.isEmpty() -> ""
                else -> CountryPresets.countries.find { it.code == selectedCountryCode }
                    ?.let { "${it.name} (${it.code})" }
                    ?: selectedCountryCode
            },
            onValueChange = {},
            readOnly = true,
            label = { Text("国家码") },
            placeholder = { Text("请选择目标国家") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            CountryPresets.countries.forEach { countryInfo ->
                DropdownMenuItem(
                    text = { Text("${countryInfo.name} (${countryInfo.code})") },
                    onClick = {
                        onCountryCodeSelected(countryInfo.code)
                        onExpandedChange(false)
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("自定义国家码...") },
                onClick = {
                    onCustomSelected()
                    onExpandedChange(false)
                }
            )
        }
    }
}

@Composable
private fun CustomCountryCodeInput(
    value: String,
    onValueChange: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("输入 2 位字母国家码") },
        placeholder = { Text("如: JP、US、TW") },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "清除")
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { focusManager.clearFocus() }
        ),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarrierSelector(
    selectedCarrier: PresetCarriers.CarrierPreset?,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCarrierSelected: (PresetCarriers.CarrierPreset) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = isExpanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = selectedCarrier?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("运营商名称") },
            placeholder = { Text("选择目标运营商（可选）") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            PresetCarriers.presets
                .groupBy { it.region }
                .forEach { (region, carriers) ->
                    if (region.isNotEmpty()) {
                        val regionName = CountryPresets.countries.find { it.code == region }?.name ?: region
                        Text(
                            text = regionName,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        carriers.forEach { carrier ->
                            DropdownMenuItem(
                                text = { Text(carrier.name) },
                                onClick = {
                                    onCarrierSelected(carrier)
                                    onExpandedChange(false)
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

            PresetCarriers.presets
                .filter { it.region.isEmpty() }
                .forEach { carrier ->
                    DropdownMenuItem(
                        text = { Text(carrier.name) },
                        onClick = {
                            onCarrierSelected(carrier)
                            onExpandedChange(false)
                        }
                    )
                }
        }
    }
}

@Composable
private fun CustomCarrierNameInput(
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("自定义运营商名称") },
        placeholder = { Text("输入要显示的运营商名称") },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "清除")
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ActionButtons(
    selectedSimCard: SimCardInfo?,
    selectedCountryCode: String,
    isCustomCountryCode: Boolean,
    customCountryCode: String,
    selectedCarrier: PresetCarriers.CarrierPreset?,
    customCarrierName: String,
    onReset: (SimCardInfo) -> Unit,
    onSave: (SimCardInfo) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 还原按钮
        OutlinedButton(
            onClick = { selectedSimCard?.let(onReset) },
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = selectedSimCard != null
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("还原设置")
        }

        // 保存按钮
        val canSave = selectedSimCard != null && (
            (if (isCustomCountryCode) customCountryCode.length == 2 else selectedCountryCode.isNotEmpty()) ||
            (!customCarrierName.isNullOrEmpty() || selectedCarrier != null)
        )

        Button(
            onClick = { selectedSimCard?.let(onSave) },
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = canSave
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("应用配置")
        }
    }
}
