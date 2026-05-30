package com.holopengin.instantjpdict

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DictionaryManagerDialog {
    fun show(context: Context) {
        val db = AppDatabase.getDatabase(context)
        val lifecycleOwner = context as? LifecycleOwner

        val recyclerView = RecyclerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutManager = LinearLayoutManager(context)
        }

        val adapter = DictionaryAdapter(mutableListOf()) { dict ->
            AlertDialog.Builder(context)
                .setTitle("Delete Dictionary")
                .setMessage("Are you sure you want to delete '${dict.name}'? All entries will be removed.")
                .setPositiveButton("Delete") { _, _ ->
                    val progressBar = android.widget.ProgressBar(context)
                    val dialog = AlertDialog.Builder(context)
                        .setTitle("Deleting...")
                        .setView(progressBar)
                        .setCancelable(false)
                        .show()
                    
                    lifecycleOwner?.lifecycleScope?.launch {
                        withContext(Dispatchers.IO) {
                            db.dictionaryDao().deleteEntriesForDictionary(dict.id)
                            db.dictionaryDao().deleteTagsForDictionary(dict.id)
                            db.dictionaryDao().deleteDictionary(dict.id)
                        }
                        dialog.dismiss()
                        loadDictionaries(context, recyclerView)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = vh.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                val list = adapter.dictionaries
                val item = list.removeAt(fromPos)
                list.add(toPos, item)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                lifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) {
                    adapter.dictionaries.forEachIndexed { index, dict ->
                        db.dictionaryDao().updatePriority(dict.id, index)
                    }
                }
            }
        })
        touchHelper.attachToRecyclerView(recyclerView)
        adapter.touchHelper = touchHelper

        loadDictionaries(context, recyclerView)

        AlertDialog.Builder(context)
            .setTitle("Manage Dictionaries")
            .setView(recyclerView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun loadDictionaries(context: Context, rv: RecyclerView) {
        val db = AppDatabase.getDatabase(context)
        val lifecycleOwner = context as? LifecycleOwner
            
        lifecycleOwner?.lifecycleScope?.launch {
            val dicts = withContext(Dispatchers.IO) { db.dictionaryDao().getAllDictionaries() }
            (rv.adapter as? DictionaryAdapter)?.update(dicts)
        }
    }

    private class DictionaryAdapter(
        val dictionaries: MutableList<DictionaryMeta>,
        val onDelete: (DictionaryMeta) -> Unit
    ) : RecyclerView.Adapter<DictionaryAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(android.R.id.text1)
            val delete: ImageButton = view.findViewById(android.R.id.button1)
            val handle: ImageButton = view.findViewById(android.R.id.button2)
        }

        var touchHelper: ItemTouchHelper? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layout = android.widget.LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(32, 16, 32, 16)
                
                addView(ImageButton(context).apply {
                    id = android.R.id.button2
                    setImageResource(android.R.drawable.ic_menu_sort_by_size)
                    background = null
                })
                
                addView(TextView(context).apply {
                    id = android.R.id.text1
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                
                addView(ImageButton(context).apply {
                    id = android.R.id.button1
                    setImageResource(android.R.drawable.ic_menu_delete)
                    background = null
                })
            }
            return ViewHolder(layout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val dict = dictionaries[position]
            holder.name.text = dict.name
            holder.delete.setOnClickListener { onDelete(dict) }
            holder.handle.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    holder.handle.performClick()
                    touchHelper?.startDrag(holder)
                    return@setOnTouchListener true
                }
                false
            }
        }

        override fun getItemCount() = dictionaries.size

        fun update(newList: List<DictionaryMeta>) {
            dictionaries.clear()
            dictionaries.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
