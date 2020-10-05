package org.dhis2.uicomponents.map.carousel

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.dhis2.R
import org.dhis2.data.tuples.Pair
import org.dhis2.databinding.ItemCarouselProgramEventBinding
import org.dhis2.databinding.ItemFieldValueBinding
import org.dhis2.usescases.programEventDetail.ProgramEventViewModel
import java.util.Locale

class CarouselProgramEventHolder(
    val binding: ItemCarouselProgramEventBinding,
    val onClick: (teiUid: String?, orgUnitUid: String?, eventUid: String?) -> Boolean,
    val attributeVisibilityCallback: (ProgramEventViewModel) -> Unit
) :
    RecyclerView.ViewHolder(binding.root),
    CarouselBinder<ProgramEventViewModel> {

    override fun bind(data: ProgramEventViewModel) {
        with(data) {
            binding.event = this
            itemView.setOnClickListener {
                onClick(uid(), orgUnitUid(), uid())
            }

            when {
                eventDisplayData().isNotEmpty() -> setEventValueLayout(this) {
                    attributeVisibilityCallback(this)
                }
                else -> hideEventValueLayout()
            }

            when {
                geometry() == null -> {
                    binding.noCoordinatesLabel.root.visibility = View.VISIBLE
                    binding.noCoordinatesLabel.noCoordinatesMessage.text =
                        itemView.context.getString(R.string.no_coordinates_item).format(
                            itemView.context.getString(R.string.event_event)
                                .toLowerCase(Locale.getDefault())
                        )
                }
                else -> binding.noCoordinatesLabel.root.visibility = View.INVISIBLE
            }
        }
    }

    private fun setEventValueLayout(
        programEventModel: ProgramEventViewModel,
        toggleList: () -> Unit
    ) {
        binding.showValuesButton.visibility = View.VISIBLE
        binding.showValuesButton.setOnClickListener {
            toggleList.invoke()
        }
        initValues(programEventModel.openedAttributeList, programEventModel.eventDisplayData())
    }

    private fun hideEventValueLayout() {
        binding.showValuesButton.visibility = View.INVISIBLE
        binding.dataElementListGuideline.visibility = View.INVISIBLE
        binding.dataElementList.visibility = View.GONE
        binding.dataValue.text = itemView.context.getString(R.string.no_data)
        binding.showValuesButton.setOnClickListener(null)
    }

    private fun initValues(
        valueListIsOpen: Boolean,
        dataElementValues: MutableList<Pair<String, String>>
    ) {
        binding.dataElementList.removeAllViews()
        binding.dataValue.text = null
        binding.showValuesButton.scaleY = if (valueListIsOpen) 1f else -1f
        binding.showValuesButton
            .animate()
            .scaleY(if (valueListIsOpen) -1f else 1f)
            .setDuration(500)
            .withStartAction { binding.showValuesButton.scaleY = if (valueListIsOpen) 1f else -1f }
            .withEndAction { binding.showValuesButton.scaleY = if (valueListIsOpen) -1f else 1f }
            .start()
        if (valueListIsOpen) {
            binding.dataElementListGuideline.visibility = View.VISIBLE
            binding.dataElementList.visibility = View.VISIBLE
            for (nameValuePair in dataElementValues) {
                val fieldValueBinding: ItemFieldValueBinding =
                    ItemFieldValueBinding.inflate(
                        LayoutInflater.from(binding.dataElementList.context)
                    )
                fieldValueBinding.name = nameValuePair.val0()
                fieldValueBinding.value = nameValuePair.val1()
                binding.dataElementList.addView(fieldValueBinding.root)
            }
        } else {
            binding.dataElementListGuideline.visibility = View.INVISIBLE
            binding.dataElementList.visibility = View.GONE
            val stringBuilder =
                SpannableStringBuilder()
            for (nameValuePair in dataElementValues) {
                if (nameValuePair.val1() != "-") {
                    val value =
                        SpannableString(nameValuePair.val1())
                    val colorToUse =
                        if (dataElementValues.indexOf(nameValuePair) % 2 == 0) {
                            ContextCompat.getColor(itemView.context, R.color.textPrimary)
                        } else {
                            ContextCompat.getColor(itemView.context, R.color.secondaryColor)
                        }
                    value.setSpan(
                        ForegroundColorSpan(colorToUse),
                        0,
                        value.length,
                        Spanned.SPAN_INCLUSIVE_INCLUSIVE
                    )
                    stringBuilder.append(value)
                    if (dataElementValues.indexOf(nameValuePair) != dataElementValues.size - 1) {
                        stringBuilder.append(" ")
                    }
                }
            }
            when {
                stringBuilder.toString().isEmpty() -> hideEventValueLayout()
                else -> binding.dataValue.text = stringBuilder
            }
        }
    }
}
