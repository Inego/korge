package com.soywiz.korge.ext.swf

import com.codeazur.as3swf.SWF
import com.codeazur.as3swf.data.GradientType
import com.codeazur.as3swf.data.SWFColorTransform
import com.codeazur.as3swf.data.actions.ActionGotoFrame
import com.codeazur.as3swf.data.actions.ActionPlay
import com.codeazur.as3swf.data.actions.ActionStop
import com.codeazur.as3swf.data.consts.BitmapFormat
import com.codeazur.as3swf.data.consts.GradientInterpolationMode
import com.codeazur.as3swf.data.consts.GradientSpreadMode
import com.codeazur.as3swf.data.consts.LineCapsStyle
import com.codeazur.as3swf.exporters.LoggerShapeExporter
import com.codeazur.as3swf.exporters.ShapeExporter
import com.codeazur.as3swf.exporters.ShapeExporterBoundsBuilder
import com.codeazur.as3swf.tags.*
import com.soywiz.korau.format.AudioFormats
import com.soywiz.korfl.abc.*
import com.soywiz.korge.animate.*
import com.soywiz.korge.render.TextureWithBitmapSlice
import com.soywiz.korge.view.BlendMode
import com.soywiz.korge.view.ColorTransform
import com.soywiz.korge.view.Views
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.color.*
import com.soywiz.korim.format.readBitmap
import com.soywiz.korim.vector.*
import com.soywiz.korio.error.ignoreErrors
import com.soywiz.korio.serialization.json.Json
import com.soywiz.korio.serialization.yaml.Yaml
import com.soywiz.korio.stream.openAsync
import com.soywiz.korio.util.*
import com.soywiz.korio.vfs.VfsFile
import com.soywiz.korma.Matrix2d
import com.soywiz.korma.ds.DoubleArrayList
import com.soywiz.korma.ds.IntArrayList
import com.soywiz.korma.geom.BoundsBuilder
import com.soywiz.korma.geom.Rectangle
import java.util.*
import kotlin.collections.set

data class SWFExportConfig(
	val debug: Boolean = false,
	val mipmaps: Boolean = true,
	val antialiasing: Boolean = true,
	val rasterizerMethod: Context2d.ShapeRasterizerMethod = Context2d.ShapeRasterizerMethod.X4,
	val exportScale: Double = 2.0,
	val minShapeSide: Int = 64,
	val maxShapeSide: Int = 512,
	val minMorphShapeSide: Int = 16,
	val maxMorphShapeSide: Int = 128,
	val exportPaths: Boolean = false
)

suspend fun VfsFile.readSWF(views: Views, config: SWFExportConfig?): AnLibrary {
	return if (config != null) this.readSWF(views, config) else this.readSWF(views)
}

suspend fun VfsFile.readSWF(views: Views, content: ByteArray? = null): AnLibrary {
	val configFile = this.appendExtension("config")
	val config = try {
		if (configFile.exists()) {
			Yaml.decodeToType<SWFExportConfig>(configFile.readString())
		} else {
			SWFExportConfig()
		}
	} catch (e: Throwable) {
		e.printStackTrace()
		SWFExportConfig()
	}
	return readSWF(views, config, content)
}

suspend fun VfsFile.readSWF(views: Views, config: SWFExportConfig, content: ByteArray? = null): AnLibrary = SwfLoaderMethod(views, config).load(content ?: this.readAll())

inline val TagPlaceObject.depth0: Int get() = this.depth - 1
inline val TagPlaceObject.clipDepth0: Int get() = this.clipDepth - 1
inline val TagRemoveObject.depth0: Int get() = this.depth - 1

private typealias SwfBlendMode = com.codeazur.as3swf.data.consts.BlendMode

val SWF.bitmaps by Extra.Property { hashMapOf<Int, Bitmap>() }

class MySwfFrame(val index0: Int, maxDepths: Int) {
	var name: String? = null
	val depths = arrayListOf<AnSymbolTimelineFrame>()
	val actions = arrayListOf<Action>()

	interface Action {
		object Stop : Action
		object Play : Action
		class Goto(val frame0: Int) : Action
		class PlaySound(val soundId: Int) : Action
	}

	val isFirst: Boolean get() = index0 == 0
	val hasStop: Boolean get() = Action.Stop in actions
	val hasGoto: Boolean get() = actions.any { it is Action.Goto }
	val hasFlow: Boolean get() = hasStop || hasGoto

	fun stop() = run { actions += Action.Stop }
	fun play() = run { actions += Action.Play }
	fun goto(frame: Int) = run { actions += Action.Goto(frame) }
	fun gotoAndStop(frame: Int) = run { goto(frame); stop() }
	fun gotoAndPlay(frame: Int) = run { goto(frame); play() }
	fun playSound(soundId: Int) = run { actions += Action.PlaySound(soundId) }
}

class MySwfTimeline {
	val frames = arrayListOf<MySwfFrame>()
}

internal val AnSymbolMovieClip.swfTimeline by Extra.Property { MySwfTimeline() }
internal val AnSymbolMovieClip.labelsToFrame0 by Extra.Property { hashMapOf<String, Int>() }

var AnSymbolMorphShape.tagDefineMorphShape by Extra.Property<TagDefineMorphShape?> { null }
var AnSymbolShape.tagDefineShape by Extra.Property<TagDefineShape?> { null }

private class SwfLoaderMethod(val views: Views, val config: SWFExportConfig) {
	lateinit var swf: SWF
	lateinit var lib: AnLibrary
	val classNameToTypes = hashMapOf<String, ABC.TypeInfo>()
	val classNameToTagId = hashMapOf<String, Int>()
	val shapesToPopulate = LinkedHashMap<AnSymbolShape, SWFShapeRasterizer>()
	val morphShapesToPopulate = arrayListOf<AnSymbolMorphShape>()
	val morphShapeRatios = hashMapOf<Int, HashSet<Double>>()

	suspend fun load(data: ByteArray): AnLibrary {
		swf = SWF().loadBytes(data)
		lib = AnLibrary(views, swf.frameRate)
		parseMovieClip(swf.tags, AnSymbolMovieClip(0, "MainTimeLine", findLimits(swf.tags)))
		for (symbol in symbols) lib.addSymbol(symbol)
		processAs3Actions()
		generateActualTimelines()
		lib.processSymbolNames()
		generateTextures()
		finalProcessing()
		return lib
	}

	private fun finalProcessing() {
		if (true) {
			//println("totalPlaceObject: $totalPlaceObject")
			//println("totalShowFrame: $totalShowFrame")
		}
	}

	fun getFrameTime(index0: Int) = (index0 * lib.msPerFrameDouble).toInt() * 1000

	suspend private fun generateActualTimelines() {
		for (symbol in lib.symbolsById.filterIsInstance<AnSymbolMovieClip>()) {
			val swfTimeline = symbol.swfTimeline
			var justAfterStopOrStart = true
			var stateStartFrame = 0
			//println(swfTimeline.frames)
			//println("## Symbol: ${symbol.name} : $symbol : ${swfTimeline.frames.size})")

			data class Subtimeline(val index: Int, var totalFrames: Int = 0, var nextState: String? = null, var nextStatePlay: Boolean = true) {
				val totalTime get() = getFrameTime(totalFrames - 1)
			}

			data class FrameInfo(val subtimeline: Subtimeline, val frameInSubTimeline: Int, val stateName: String, val startSubtimeline: Boolean, val startNamedState: Boolean) {
				val timeInSubTimeline = getFrameTime(frameInSubTimeline)
			}

			val frameInfos = java.util.ArrayList<FrameInfo>(swfTimeline.frames.size)

			// Identify referenced frames
			val referencedFrames = hashSetOf<Int>()
			for (frame in swfTimeline.frames) {
				if (frame.hasGoto) {
					val goto = frame.actions.filterIsInstance<MySwfFrame.Action.Goto>().first()
					referencedFrames += goto.frame0
				}
			}

			// Create FrameInfo
			var flow = true
			var stateName = "default"
			var frameIndex = 0
			var subtimelineIndex = -1


			val subtimelines = arrayListOf<Subtimeline>()

			for (frame in swfTimeline.frames) {
				var startNamedState = false
				var startSubtimeline = false
				if (flow) {
					stateName = when {
						frame.isFirst -> "default"
						frame.name != null -> frame.name!!
						else -> "frame${frame.index0}"
					}
					frameIndex = 0
					subtimelineIndex++
					subtimelines += Subtimeline(subtimelineIndex)
					startNamedState = true
					startSubtimeline = true
				}

				if (frame.name != null) {
					stateName = frame.name!!
					startNamedState = true
				} else if (frame.index0 in referencedFrames) {
					stateName = "frame${frame.index0}"
					startNamedState = true
				}

				val subtimeline = subtimelines[subtimelineIndex]
				subtimeline.totalFrames++
				flow = frame.hasFlow

				frameInfos += FrameInfo(subtimeline, frameIndex, stateName, startSubtimeline, startNamedState)

				frameIndex++
			}

			// Compute flow
			for (frame in swfTimeline.frames) {
				val info = frameInfos[frame.index0]
				val isLast = frame.index0 == swfTimeline.frames.last().index0

				if (isLast) {
					info.subtimeline.nextState = "default"
					info.subtimeline.nextStatePlay = true
				}

				if (frame.hasFlow) {
					for (action in frame.actions) {
						when (action) {
							is MySwfFrame.Action.Goto -> {
								info.subtimeline.nextState = frameInfos[action.frame0].stateName
							}
							is MySwfFrame.Action.Stop -> {
								info.subtimeline.nextStatePlay = false
							}
							is MySwfFrame.Action.Play -> {
								info.subtimeline.nextStatePlay = true
							}
						}
					}
				}
			}

			val totalDepths = symbol.limits.totalDepths
			var currentSubTimeline = AnSymbolMovieClipSubTimeline(totalDepths)

			//println("-------------")
			//for (frameInfo in frameInfos) println(frameInfo)

			val lastDepths = kotlin.arrayOfNulls<AnSymbolTimelineFrame?>(totalDepths)

			for (frame in swfTimeline.frames) {
				val info = frameInfos[frame.index0]
				val currentTime = info.timeInSubTimeline
				//val isLast = frame.index0 == swfTimeline.frames.last().index0

				// Subtimelines
				if (info.startSubtimeline) {
					currentSubTimeline = AnSymbolMovieClipSubTimeline(totalDepths)
					val subtimeline = info.subtimeline
					currentSubTimeline.totalTime = subtimeline.totalTime
					currentSubTimeline.nextState = subtimeline.nextState
					currentSubTimeline.nextStatePlay = subtimeline.nextStatePlay

					//println("$currentSubTimeline : $subtimeline")

					//println("currentSubTimeline.totalTime = info.subtimeline.totalTime <- ${info.subtimeline.totalTime}")
					if (frame.isFirst) {
						symbol.states["default"] = AnSymbolMovieClipState("default", currentSubTimeline, 0)
					}
				}
				// States
				if (info.startNamedState) {
					symbol.states[info.stateName] = AnSymbolMovieClipState(info.stateName, currentSubTimeline, info.timeInSubTimeline)
				}

				// Compute frame
				for (n in 0 until frame.depths.size) {
					val lastDepth = lastDepths[n]
					val depth = frame.depths[n]
					if (depth != lastDepth) {
						currentSubTimeline.timelines[depth.depth].add(info.timeInSubTimeline, depth)
						lastDepths[n] = depth
					}
				}

				// Compute actions
				val anActions = arrayListOf<AnAction>()
				for (it in frame.actions) {
					when (it) {
						is MySwfFrame.Action.PlaySound -> {
							anActions += AnPlaySoundAction(it.soundId)
						}
					}
				}
				if (anActions.isNotEmpty()) currentSubTimeline.actions.add(currentTime, AnActions(anActions))
			}
		}
	}

	suspend private fun processAs3Actions() {
		for ((className, tagId) in classNameToTagId) {
			lib.symbolsById[tagId].name = className
			val type = classNameToTypes[className] ?: continue
			val symbol = (lib.symbolsById[tagId] as? AnSymbolMovieClip?) ?: continue
			val abc = type.abc
			val labelsToFrame0 = symbol.labelsToFrame0

			//println("$tagId :: $className :: $symbol :: $type")
			for (trait in type.instanceTraits) {
				val simpleName = trait.name.simpleName
				//println(" - " + trait.name.simpleName)
				if (simpleName.startsWith("frame")) {
					val frame = ignoreErrors { simpleName.substr(5).toInt() } ?: continue
					val frame0 = frame - 1
					val traitMethod = (trait as ABC.TraitMethod?) ?: continue
					val methodDesc = abc.methodsDesc[traitMethod.methodIndex]
					val body = methodDesc.body ?: continue
					//println("FRAME: $frame0")
					//println(body.ops)

					var lastValue: Any? = null
					for (op in body.ops) {
						when (op.opcode) {
							AbcOpcode.PushByte -> lastValue = (op as AbcIntOperation).value
							AbcOpcode.PushShort -> lastValue = (op as AbcIntOperation).value
							AbcOpcode.PushInt -> lastValue = (op as AbcIntOperation).value
							AbcOpcode.PushUInt -> lastValue = (op as AbcIntOperation).value
							AbcOpcode.PushString -> lastValue = (op as AbcStringOperation).value
							AbcOpcode.CallPropVoid -> {
								val call = (op as AbcMultinameIntOperation)
								val callMethodName = call.multiname.simpleName
								val frameData = symbol.swfTimeline.frames[frame0]
								when (callMethodName) {
									"gotoAndPlay", "gotoAndStop" -> {
										val gotoFrame0 = when (lastValue) {
											is String -> labelsToFrame0[lastValue] ?: 0
											is Int -> lastValue - 1
											else -> 0
										}
										if (callMethodName == "gotoAndStop") {
											frameData.gotoAndStop(gotoFrame0)
										} else {
											frameData.gotoAndPlay(gotoFrame0)
										}
									}
									"play" -> frameData.play()
									"stop" -> frameData.stop()

									else -> {
										//println("method: $callMethodName")
									}
								}
								lastValue = null
							}
							else -> Unit
						}
					}
				}
			}
		}
	}


	suspend private fun generateTextures() {
		val itemsInAtlas = LinkedHashMap<(TextureWithBitmapSlice) -> Unit, BitmapWithScale>()

		for ((shape, rasterizer) in shapesToPopulate) {
			itemsInAtlas.put({ texture -> shape.textureWithBitmap = texture }, rasterizer.imageWithScale)
		}

		for (morph in morphShapesToPopulate) {
			val tag = morph.tagDefineMorphShape!!
			val ratios = (morphShapeRatios[tag.characterId] ?: setOf<Double>()).sorted()
			val MAX_RATIOS = 24
			val aratios = if (ratios.size > MAX_RATIOS) (0 until MAX_RATIOS).map { it.toDouble() / (MAX_RATIOS - 1).toDouble() } else ratios
			for (ratio in aratios) {
				val bb = ShapeExporterBoundsBuilder()
				try {
					tag.export(bb, ratio)
				} catch (e: Throwable) {
					e.printStackTrace()
				}
				val bounds = bb.bb.getBounds()
				//bb.bb.add()
				val rasterizer = SWFShapeRasterizer(
					swf, config.debug, bounds,
					{
						try {
							tag.export(it, ratio)
						} catch (e: Throwable) {
							e.printStackTrace()
						}
					},
					config.rasterizerMethod,
					antialiasing = config.antialiasing,
					requestScale = config.exportScale,
					minSide = config.minMorphShapeSide,
					maxSide = config.maxMorphShapeSide
				)
				itemsInAtlas.put({ texture -> morph.texturesWithBitmap.add((ratio * 1000).toInt(), texture) }, rasterizer.imageWithScale)
			}
		}

		for ((processor, texture) in itemsInAtlas.toAtlas(views, config.mipmaps)) processor(texture)
	}

	fun findLimits(tags: Iterable<ITag>): AnSymbolLimits {
		var maxDepth = -1
		var totalFrames = 0
		val items = hashSetOf<Pair<Int, Int>>()
		// Find limits
		for (it in tags) {
			when (it) {
				is TagPlaceObject -> {
					if (it.hasCharacter) {
						items += it.depth0 to it.characterId
					}
					maxDepth = Math.max(maxDepth, it.depth0)
					//if (it.hasClipDepth) maxDepth = Math.max(maxDepth, it.clipDepth0)
				}
				is TagShowFrame -> {
					totalFrames++
				}
			}
		}
		return AnSymbolLimits(maxDepth + 1, totalFrames, items.size, (totalFrames * lib.msPerFrameDouble).toInt())
	}

	val symbols = arrayListOf<AnSymbol>()

	fun registerBitmap(charId: Int, bmp: Bitmap, name: String? = null) {
		swf.bitmaps[charId] = bmp
		symbols += AnSymbolBitmap(charId, name, bmp)
		//showImageAndWait(bmp)
	}

	var totalPlaceObject = 0
	var totalShowFrame = 0

	var spritesById = hashMapOf<Int, AnSymbolMovieClip>()

	suspend fun parseMovieClip(tags: Iterable<ITag>, mc: AnSymbolMovieClip) {
		symbols += mc

		val swfTimeline = mc.swfTimeline
		val labelsToFrame0 = mc.labelsToFrame0
		val uniqueIds = hashMapOf<Pair<Int, Int>, Int>()

		class DepthInfo(val depth: Int) {
			var uid: Int = -1
			var charId: Int = -1
			var clipDepth: Int = -1
			var name: String? = null
			var colorTransform: ColorTransform = ColorTransform.identity
			var ratio: Double = 0.0
			var matrix: Matrix2d = Matrix2d()
			var blendMode: BlendMode = BlendMode.INHERIT

			fun reset() {
				uid = -1
				ratio = 0.0
				charId = -1
				clipDepth = -1
				colorTransform = ColorTransform.identity
				name = null
				matrix = Matrix2d()
				blendMode = BlendMode.INHERIT
			}

			fun toFrameElement() = AnSymbolTimelineFrame(
				depth = depth,
				clipDepth = clipDepth,
				uid = uid,
				ratio = ratio,
				name = name,
				transform = matrix,
				colorTransform = colorTransform,
				blendMode = blendMode
			)
		}

		val depths = Array(mc.limits.totalDepths) { DepthInfo(it) }

		fun getUid(depth: Int): Int {
			val charId = depths[depth].charId
			return uniqueIds.getOrPut(depth to charId) {
				val uid = uniqueIds.size
				mc.uidInfo[uid] = AnSymbolUidDef(charId)
				uid
			}
		}

		// Add frames and read labels information
		for (it in tags) {
			val currentFrame = swfTimeline.frames.size
			when (it) {
				is TagDefineSceneAndFrameLabelData -> {
					mc.labelsToFrame0 += it.frameLabels.map { it.name to it.frameNumber - 1 }
				}
				is TagFrameLabel -> {
					mc.labelsToFrame0[it.frameName] = currentFrame
				}
				is TagShowFrame -> {
					swfTimeline.frames += MySwfFrame(currentFrame, mc.limits.totalDepths)
				}
			}
		}

		// Populate frame names
		for ((name, index) in mc.labelsToFrame0) swfTimeline.frames[index].name = name

		val depthsChanged = BitSet(depths.size)
		var currentFrame = 0
		for (it in tags) {
			//println("Tag: $it")
			val currentTime = getFrameTime(currentFrame)
			val swfCurrentFrame by lazy { mc.swfTimeline.frames[currentFrame] }
			when (it) {
				is TagDefineSceneAndFrameLabelData -> Unit
				is TagFrameLabel -> Unit
				is TagFileAttributes -> Unit
				is TagSetBackgroundColor -> {
					lib.bgcolor = decodeSWFColor(it.color)
				}
				is TagProtect -> Unit // ignore
				is TagDefineFont -> {
				}
				is TagDefineFontName -> {
				}
				is TagDefineFontAlignZones -> {
				}
				is TagDefineEditText -> {
					symbols += AnTextFieldSymbol(it.characterId, null, it.initialText ?: "", it.bounds.rect)
				}
				is TagCSMTextSettings -> {
				}
				is TagDoAction -> {
					for (action in it.actions) {
						when (action) {
							is ActionStop -> swfCurrentFrame.stop()
							is ActionPlay -> swfCurrentFrame.play()
							is ActionGotoFrame -> swfCurrentFrame.goto(action.frame)
						}
					}
				}
				is TagSoundStreamHead -> {
				}
				is TagDefineSound -> {
					val soundBytes = it.soundData.cloneToNewByteArray()
					val audioData = try {
						AudioFormats.decode(soundBytes.openAsync())
					} catch (e: Throwable) {
						e.printStackTrace()
						null
					}
					symbols += AnSymbolSound(it.characterId, null, audioData, soundBytes)
				}
				is TagStartSound -> {
					swfCurrentFrame.playSound(it.soundId)
				}
				is TagJPEGTables -> {
					println("Unhandled tag: $it")
				}
				is TagDefineBits, is TagDefineBitsLossless -> {
					var fbmp: Bitmap = Bitmap32(1, 1)
					it as IDefinitionTag

					when (it) {
						is TagDefineBitsJPEG2 -> {
							val bitsData = it.bitmapData.cloneToNewByteArray()
							val nativeBitmap = try {
								bitsData.openAsync().readBitmap()
							} catch (e: Throwable) {
								e.printStackTrace()
								Bitmap32(1, 1)
							}
							//println(nativeBitmap)
							val bmp = nativeBitmap.toBMP32()
							fbmp = bmp

							if (it is TagDefineBitsJPEG3) {
								val fmaskinfo = it.bitmapAlphaData.cloneToNewFlashByteArray()
								fmaskinfo.uncompressInWorker("zlib")
								val maskinfo = fmaskinfo.cloneToNewByteArray()
								//val bmpAlpha = nativeImageFormatProvider.decode(maskinfo)
								//showImageAndWait(bmpAlpha)
								bmp.writeChannel(BitmapChannel.ALPHA, Bitmap8(bmp.width, bmp.height, maskinfo))
							}

							//showImageAndWait(bmp)

							//println(bmp)
							//for (y in 0 until bmp.height) {
							//	for (x in 0 until bmp.width) System.out.printf("%08X,", bmp[x, y])
							//	println()
							//}
						}
						is TagDefineBitsLossless -> {
							val isRgba = it.hasAlpha
							val funcompressedData = it.zlibBitmapData.cloneToNewFlashByteArray()
							funcompressedData.uncompressInWorker("zlib")
							val uncompressedData = funcompressedData.cloneToNewByteArray()
							when (it.bitmapFormat) {
								BitmapFormat.BIT_8 -> {
									val bmp = Bitmap8(it.bitmapWidth, it.bitmapHeight)
									fbmp = bmp
								}
								BitmapFormat.BIT_15 -> {
									fbmp = Bitmap32(it.bitmapWidth, it.bitmapHeight, BGRA_5551.decode(uncompressedData))
								}
								BitmapFormat.BIT_24_32 -> {
									val colorFormat = if (isRgba) BGRA else RGB
									fbmp = Bitmap32(it.bitmapWidth, it.bitmapHeight, colorFormat.decode(uncompressedData, littleEndian = false))
								}
								else -> Unit
							}

						}
					}

					registerBitmap(it.characterId, fbmp, null)
				}
				is TagDefineShape -> {
					val tag = it
					val rasterizer = SWFShapeRasterizer(
						swf,
						config.debug,
						tag.shapeBounds.rect,
						{ tag.export(it) },
						config.rasterizerMethod,
						antialiasing = config.antialiasing,
						requestScale = config.exportScale,
						minSide = config.minShapeSide,
						maxSide = config.maxShapeSide
					)
					//val rasterizer = LoggerShapeExporter(SWFShapeRasterizer(swf, debug, it))
					val symbol = AnSymbolShape(it.characterId, null, rasterizer.bounds, null, rasterizer.path)
					symbol.tagDefineShape = it
					symbols += symbol
					shapesToPopulate += symbol to rasterizer
				}
				is TagDefineMorphShape -> {
					val startBounds = it.startBounds.rect
					val endBounds = it.endBounds.rect
					val bounds = BoundsBuilder()
						.add(startBounds)
						.add(endBounds)
						.add(it.startEdgeBounds.rect)
						.add(it.endEdgeBounds.rect)
						.getBounds()

					val bounds2 = bounds.copy(width = bounds.width + 100, height = bounds.height + 100)

					//println("${startBounds.toStringBounds()}, ${endBounds.toStringBounds()} -> ${bounds.toStringBounds()}")

					val symbol = AnSymbolMorphShape(it.characterId, null, bounds2)
					symbol.tagDefineMorphShape = it
					symbols += symbol
					morphShapesToPopulate += symbol
				}
				is TagDoABC -> {
					classNameToTypes += it.abc.typesInfo.map { it.name.toString() to it }.toMap()
				}
				is TagSymbolClass -> {
					classNameToTagId += it.symbols.filter { it.name != null }.map { it.name!! to it.tagId }.toMap()
				}
				is TagDefineSprite -> {
					val childMc = AnSymbolMovieClip(it.characterId, null, findLimits(it.tags))
					spritesById[it.characterId] = childMc
					parseMovieClip(it.tags, childMc)
				}
				is TagDefineScalingGrid -> {
					val childMc = spritesById[it.characterId]
					if (childMc != null) {
						childMc.ninePatch = it.splitter.rect
					}
				}
				is TagPlaceObject -> {
					totalPlaceObject++
					//val depthId = if (it.hasClipDepth) it.clipDepth0 else it.depth0
					//val clipDepthId = if (it.hasClipDepth) it.depth0 else -1

					val depthId = it.depth0
					val clipDepthId = if (it.hasClipDepth) it.clipDepth0 - 1 else -1

					val depth = depths[depthId]

					if (it.hasCharacter) depth.charId = it.characterId
					if (it.hasClipDepth) depth.clipDepth = clipDepthId
					if (it.hasName) depth.name = it.instanceName
					//if (it.hasBlendMode) depth.blendMode = it.blendMode
					if (it.hasColorTransform) {
						val ct = it.colorTransform!!.toColorTransform()
						depth.colorTransform = ct
						//allColorTransforms += ct
						//println(depth.colorTransform)
					}
					if (it.hasMatrix) {
						val m = it.matrix!!.matrix
						depth.matrix = m
						//allMatrices += m
					}
					if (it.hasBlendMode) depth.blendMode = when (it.blendMode) {
						SwfBlendMode.NORMAL_0, SwfBlendMode.NORMAL_1 -> BlendMode.NORMAL
						SwfBlendMode.ADD -> BlendMode.ADD
						else -> BlendMode.NORMAL
					}
					val uid = getUid(depthId)
					val metaData = it.metaData
					if (metaData != null && metaData is Map<*, *> && "props" in metaData) {
						val uidInfo = mc.uidInfo[uid]
						val eprops = ignoreErrors { Json.decode(metaData["props"].toString()) as Map<String, String> }
						if (eprops != null) uidInfo.extraProps += eprops
						//println(depth.extraProps)
					}

					if (it.hasRatio) {
						depth.ratio = it.ratiod
						val ratios = morphShapeRatios.getOrPut(depth.charId) { hashSetOf() }
						ratios += it.ratiod
					}

					depth.uid = uid
					depthsChanged[depthId] = true
				}
				is TagRemoveObject -> {
					depths[it.depth0].reset()
					depthsChanged[it.depth0] = true
				}
				is TagShowFrame -> {
					totalShowFrame++
					for (depth in depths) {
						if (depthsChanged[depth.depth]) swfCurrentFrame.depths += depth.toFrameElement()
					}
					depthsChanged.clear()
					currentFrame++
				}
				is TagEnd -> {
				}
				else -> {
					println("Unhandled tag $it")
				}
			}
		}
	}
}

fun SWFColorTransform.toColorTransform() = ColorTransform(rMult, gMult, bMult, aMult, rAdd, gAdd, bAdd, aAdd)

fun decodeSWFColor(color: Int, alpha: Double = 1.0) = RGBA.pack(color.extract8(16), color.extract8(8), color.extract8(0), (alpha * 255).toInt())

class SWFShapeRasterizer(
	val swf: SWF,
	val debug: Boolean,
	val bounds: Rectangle,
	val export: (ShapeExporter) -> Unit,
	val rasterizerMethod: Context2d.ShapeRasterizerMethod,
	val antialiasing: Boolean,
	val requestScale: Double = 2.0,
	val minSide: Int = 16,
	val maxSide: Int = 512
) : ShapeExporter() {
	//val bounds: Rectangle = dshape.shapeBounds.rect

	//val bmp = Bitmap32(bounds.width.toIntCeil(), bounds.height.toIntCeil())

	val realBoundsWidth = Math.max(1, bounds.width.toIntCeil())
	val realBoundsHeight = Math.max(1, bounds.height.toIntCeil())

	val desiredBoundsWidth = (realBoundsWidth * requestScale).toInt()
	val desiredBoundsHeight = (realBoundsHeight * requestScale).toInt()

	val limitBoundsWidth = desiredBoundsWidth.clamp(minSide, maxSide)
	val limitBoundsHeight = desiredBoundsHeight.clamp(minSide, maxSide)

	val actualScale = Math.min(limitBoundsWidth.toDouble() / realBoundsWidth.toDouble(), limitBoundsHeight.toDouble() / realBoundsHeight.toDouble())

	//val actualScale = 0.5

	val actualBoundsWidth = (realBoundsWidth * actualScale).toInt()
	val actualBoundsHeight = (realBoundsHeight * actualScale).toInt()

	var cshape = CompoundShape(listOf())
	val shapes = arrayListOf<Shape>()

	val actualShape by lazy {
		export(if (debug) LoggerShapeExporter(this) else this)
		//this.dshape.export(if (debug) LoggerShapeExporter(this) else this)
		cshape
	}

	val image by lazy {
		val image = NativeImage(actualBoundsWidth, actualBoundsHeight)
		val ctx = image.getContext2d(antialiasing = antialiasing)
		ctx.scale(actualScale, actualScale)
		ctx.translate(-bounds.x, -bounds.y)
		ctx.drawShape(actualShape, rasterizerMethod)

		//println(actualShape.toSvg(scale = 1.0 / 20.0).toOuterXmlIndented())

		image
	}
	val imageWithScale by lazy {
		BitmapWithScale(image, actualScale, bounds)
	}

	var drawingFill = true

	var apath = GraphicsPath()
	val path = GraphicsPath()
	override fun beginShape() {
		//ctx.beginPath()
	}

	override fun endShape() {
		cshape = CompoundShape(shapes)
		//ctx.closePath()
	}

	override fun beginFills() {
		flush()
		drawingFill = true
	}

	override fun endFills() {
		flush()
	}

	override fun beginLines() {
		flush()
		drawingFill = false
	}

	override fun endLines() {
		flush()
	}

	fun GradientSpreadMode.toCtx() = when (this) {
		GradientSpreadMode.PAD -> Context2d.CycleMethod.NO_CYCLE
		GradientSpreadMode.REFLECT -> Context2d.CycleMethod.REFLECT
		GradientSpreadMode.REPEAT -> Context2d.CycleMethod.REPEAT
	}

	var fillStyle: Context2d.Paint = Context2d.None

	override fun beginFill(color: Int, alpha: Double) {
		flush()
		drawingFill = true
		fillStyle = Context2d.Color(decodeSWFColor(color, alpha))
	}

	private fun createGradientPaint(type: GradientType, colors: List<Int>, alphas: List<Double>, ratios: List<Int>, matrix: Matrix2d, spreadMethod: GradientSpreadMode, interpolationMethod: GradientInterpolationMode, focalPointRatio: Double): Context2d.Gradient {
		val aratios = DoubleArrayList(ratios.map { it.toDouble() / 255.0 }.toDoubleArray())
		val acolors = IntArrayList(colors.zip(alphas).map { decodeSWFColor(it.first, it.second) }.toIntArray())

		val m2 = Matrix2d()
		m2.copyFrom(matrix)


		m2.pretranslate(-0.5, -0.5)
		m2.prescale(1638.4 / 2.0, 1638.4 / 2.0)

		m2.scale(20.0, 20.0)

		//m2.prescale(1.0 / 20.0, 1.0 / 20.0)
		//m2.prescale(1.0 / 20.0, 1.0 / 20.0)

		val imethod = when (interpolationMethod) {
			GradientInterpolationMode.NORMAL -> Context2d.Gradient.InterpolationMethod.NORMAL
			GradientInterpolationMode.LINEAR -> Context2d.Gradient.InterpolationMethod.LINEAR
		}

		return when (type) {
			GradientType.LINEAR -> Context2d.LinearGradient(-1.0, 0.0, +1.0, 0.0, aratios, acolors, spreadMethod.toCtx(), m2, imethod)
			GradientType.RADIAL -> Context2d.RadialGradient(focalPointRatio, 0.0, 0.0, 0.0, 0.0, 1.0, aratios, acolors, spreadMethod.toCtx(), m2, imethod)
		}
	}

	override fun beginGradientFill(type: GradientType, colors: List<Int>, alphas: List<Double>, ratios: List<Int>, matrix: Matrix2d, spreadMethod: GradientSpreadMode, interpolationMethod: GradientInterpolationMode, focalPointRatio: Double) {
		flush()
		drawingFill = true
		fillStyle = createGradientPaint(type, colors, alphas, ratios, matrix, spreadMethod, interpolationMethod, focalPointRatio)
	}

	override fun beginBitmapFill(bitmapId: Int, matrix: Matrix2d, repeat: Boolean, smooth: Boolean) {
		flush()
		drawingFill = true
		val bmp = swf.bitmaps[bitmapId] ?: Bitmap32(1, 1)
		//fillStyle = Context2d.BitmapPaint(bmp, matrix.clone(), repeat, smooth)
		fillStyle = Context2d.BitmapPaint(bmp, matrix.clone().scale(20.0, 20.0), repeat, smooth)
		//fillStyle = Context2d.BitmapPaint(bmp, matrix.clone().prescale(20.0, 20.0), repeat, smooth)
	}

	override fun endFill() {
		flush()
	}

	private fun __flushFill() {
		if (apath.isEmpty()) return
		shapes += FillShape(apath, null, fillStyle, Matrix2d().prescale(1.0 / 20.0, 1.0 / 20.0))
		apath = GraphicsPath()
	}

	private fun __flushStroke() {
		if (apath.isEmpty()) return
		shapes += PolylineShape(apath, null, strokeStyle, Matrix2d().prescale(1.0 / 20.0, 1.0 / 20.0), lineWidth, true, "normal", lineCap, lineCap, "joints", 10.0)
		apath = GraphicsPath()
	}

	private fun flush() {
		if (drawingFill) {
			__flushFill()
		} else {
			__flushStroke()
		}
	}

	private var lineWidth: Double = 1.0
	private var lineCap: Context2d.LineCap = Context2d.LineCap.ROUND
	private var strokeStyle: Context2d.Paint = Context2d.Color(Colors.BLACK)

	override fun lineStyle(thickness: Double, color: Int, alpha: Double, pixelHinting: Boolean, scaleMode: String, startCaps: LineCapsStyle, endCaps: LineCapsStyle, joints: String?, miterLimit: Double) {
		flush()
		drawingFill = false
		//println("pixelHinting: $pixelHinting, scaleMode: $scaleMode, miterLimit=$miterLimit")
		lineWidth = thickness * 20.0
		strokeStyle = Context2d.Color(decodeSWFColor(color, alpha))
		lineCap = when (startCaps) {
			LineCapsStyle.NO -> Context2d.LineCap.BUTT
			LineCapsStyle.ROUND -> Context2d.LineCap.ROUND
			LineCapsStyle.SQUARE -> Context2d.LineCap.SQUARE
		}
	}

	override fun lineGradientStyle(type: GradientType, colors: List<Int>, alphas: List<Double>, ratios: List<Int>, matrix: Matrix2d, spreadMethod: GradientSpreadMode, interpolationMethod: GradientInterpolationMode, focalPointRatio: Double) {
		flush()
		drawingFill = false
		strokeStyle = createGradientPaint(type, colors, alphas, ratios, matrix, spreadMethod, interpolationMethod, focalPointRatio)
	}

	private fun Double.fix() = (this * 20).toInt().toDouble()
	//private fun Double.fix() = this.toInt()

	override fun moveTo(x: Double, y: Double) {
		apath.moveTo(x.fix(), y.fix())
		if (drawingFill) path.moveTo(x, y)
	}

	override fun lineTo(x: Double, y: Double) {
		apath.lineTo(x.fix(), y.fix())
		if (drawingFill) path.lineTo(x, y)
	}

	override fun curveTo(controlX: Double, controlY: Double, anchorX: Double, anchorY: Double) {
		apath.quadTo(controlX.fix(), controlY.fix(), anchorX.fix(), anchorY.fix())
		if (drawingFill) path.quadTo(controlX, controlY, anchorX, anchorY)
	}

	override fun closePath() {
		apath.close()
		if (drawingFill) path.close()
	}
}
