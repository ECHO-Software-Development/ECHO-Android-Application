package com.example.echo

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageListAdapter(context: Context) :
    ArrayAdapter<MessengerMessage>(context, R.layout.message_list_item) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.message_list_item, parent, false)

        val message = getItem(position)
        view.findViewById<TextView>(R.id.message_sender).text = message?.sender.orEmpty()
        view.findViewById<TextView>(R.id.message_text).text = message?.text.orEmpty()
        view.findViewById<TextView>(R.id.message_time).text =
            message?.let { timeFormat.format(Date(it.timestamp)) }.orEmpty()

        return view
    }
}
